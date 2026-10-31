//! Stream framing shared by every channel.
//!
//! Each QUIC stream starts with `[channel id][channel version]`, then carries
//! varint-length-delimited protobuf messages (bulk channels switch to raw bytes
//! after their header message).

use anyhow::{Context, Result, bail};
use prost::Message;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const CH_CONTROL: u8 = 0x00;
pub const CH_FILE: u8 = 0x01;
/// Small realtime messages: one `Event` per uni stream (notifications, clipboard, media...).
pub const CH_EVENTS: u8 = 0x02;
/// Screen mirroring / control: one bi stream per session, FSP1 bytes (docs/screen-protocol.md).
pub const CH_SCREEN: u8 = 0x03;
pub const CHANNEL_VERSION: u8 = 1;

/// Largest control message we accept; bounds memory a peer can make us allocate.
pub const MAX_MSG: usize = 1 << 20;

pub async fn write_prefix<W: AsyncWrite + Unpin>(w: &mut W, channel: u8) -> Result<()> {
    w.write_all(&[channel, CHANNEL_VERSION]).await?;
    Ok(())
}

/// Returns the channel id; fails on an unknown channel version.
pub async fn read_prefix<R: AsyncRead + Unpin>(r: &mut R) -> Result<u8> {
    let mut b = [0u8; 2];
    r.read_exact(&mut b).await.context("read stream prefix")?;
    if b[1] != CHANNEL_VERSION {
        bail!("channel {:#04x}: unsupported version {}", b[0], b[1]);
    }
    Ok(b[0])
}

pub async fn write_msg<W: AsyncWrite + Unpin, M: Message>(w: &mut W, m: &M) -> Result<()> {
    w.write_all(&m.encode_length_delimited_to_vec()).await?;
    Ok(())
}

pub async fn read_msg<R: AsyncRead + Unpin, M: Message + Default>(r: &mut R) -> Result<M> {
    // varint length, at most 10 bytes
    let mut len: u64 = 0;
    for i in 0..10 {
        let b = r.read_u8().await.context("read message length")?;
        len |= u64::from(b & 0x7f) << (7 * i);
        if b & 0x80 == 0 {
            break;
        }
        if i == 9 {
            bail!("message length varint too long");
        }
    }
    let len = usize::try_from(len)?;
    if len > MAX_MSG {
        bail!("message of {len} bytes exceeds limit");
    }
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf).await.context("read message body")?;
    Ok(M::decode(buf.as_slice())?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::Hello;

    #[tokio::test]
    async fn roundtrip_and_limits() {
        let (mut a, mut b) = tokio::io::duplex(4096);
        write_prefix(&mut a, CH_FILE).await.unwrap();
        let hello = Hello {
            protocol_version: 1,
            device_name: "Mac".into(),
            channels: vec![0, 1],
            addrs: vec!["192.168.1.2:4000".into()],
        };
        write_msg(&mut a, &hello).await.unwrap();
        assert_eq!(read_prefix(&mut b).await.unwrap(), CH_FILE);
        assert_eq!(read_msg::<_, Hello>(&mut b).await.unwrap(), hello);

        // oversized length is rejected before allocating
        a.write_all(&[0xff, 0xff, 0xff, 0x7f]).await.unwrap();
        assert!(read_msg::<_, Hello>(&mut b).await.is_err());
    }
}
