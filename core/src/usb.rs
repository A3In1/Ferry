//! USB link: the same QUIC session, carried over an `adb forward` TCP tunnel.
//!
//! adb tunnels TCP only, so each side runs a small shim: QUIC datagrams from the local
//! endpoint are length-framed into the TCP stream and replayed as UDP on the far side.
//! iroh just sees one more UDP path (a 127.0.0.1 address), so encryption, pairing and
//! channels are identical to Wi-Fi.
//!
//! Phone: [`Node::usb_serve`] listens on 127.0.0.1:[`USB_PORT`]. Mac: [`Node::usb_watch`]
//! polls `adb devices`, runs `adb forward tcp:0 tcp:USB_PORT` and attaches.

use std::path::PathBuf;
use std::sync::Arc;

use anyhow::Result;

use crate::node::Node;

/// TCP port the phone app listens on (loopback only, reachable through adb forward).
pub const USB_PORT: u16 = 47100;

/// One phone currently attached over USB.
#[derive(Debug, Clone)]
pub struct UsbLink {
    pub serial: String,  // adb serial
    pub peer_id: String, // the phone's device id (learned from the bridge hello)
}

impl Node {
    /// Phone side: accept USB bridges on 127.0.0.1:`port` (returns once listening).
    pub async fn usb_serve(self: &Arc<Self>, port: u16) -> Result<()> {
        todo!()
    }

    /// Mac side: attach a bridge to an already-forwarded TCP port; returns the phone's id.
    pub async fn usb_attach(self: &Arc<Self>, tcp_port: u16, serial: String) -> Result<String> {
        todo!()
    }

    /// Mac side: background task that attaches/detaches phones as they are plugged in.
    /// `adb` = explicit adb binary, else found on PATH / ANDROID_HOME / Homebrew.
    pub fn usb_watch(self: &Arc<Self>, adb: Option<PathBuf>) -> Result<()> {
        todo!()
    }

    /// Phones attached right now.
    pub fn usb_links(&self) -> Vec<UsbLink> {
        todo!()
    }

    /// Mac side: show a fresh pairing ticket on the USB phone (`serial`, or the only one)
    /// by opening the app's `ferry://pair` deep link via adb. No QR scan needed.
    pub async fn usb_push_ticket(&self, serial: Option<String>) -> Result<()> {
        todo!()
    }
}
