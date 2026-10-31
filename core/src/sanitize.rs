//! Make peer-supplied file names safe to create inside a destination folder.

/// Longest file name (in UTF-8 bytes) that APFS, ext4 and f2fs all accept.
pub const MAX_NAME_BYTES: usize = 255;

/// Returns a single safe path component (never empty, never `.`/`..`, no separators).
///
/// A name containing `/` or `\` keeps only its last non-empty component, so
/// `../../etc/passwd` becomes `passwd`. Invisible/spoofing characters (controls,
/// bidi overrides, zero-width) are dropped and characters FAT/Windows forbid are
/// replaced with `_`, because Android shared storage rejects them. Leading dots are
/// kept (dotfiles are legitimate).
pub fn file_name(name: &str) -> String {
    let cleaned: String = name
        .chars()
        .filter(|&c| !is_invisible(c))
        .map(|c| if matches!(c, '<' | '>' | ':' | '"' | '|' | '?' | '*') { '_' } else { c })
        .collect();
    let last = cleaned.rsplit(['/', '\\']).find(|s| !s.is_empty()).unwrap_or("");
    let mut out = tidy(last).to_owned();
    // `CON.txt` is unopenable on Windows even with an extension.
    let base = out.split('.').next().unwrap_or("").trim_end().to_ascii_uppercase();
    if is_reserved(&base) {
        out.insert(0, '_');
    }
    let out = tidy(&fit(&out, "")).to_owned();
    if out.is_empty() { "file".into() } else { out }
}

/// `photo.jpg` + 2 → `photo (2).jpg`, shortening the stem so the result stays ≤255 bytes.
pub fn numbered(name: &str, n: u32) -> String {
    fit(name, &format!(" ({n})"))
}

/// Truncates the stem of `name` so `stem + suffix + extension` fits in
/// [`MAX_NAME_BYTES`], never splitting a character.
fn fit(name: &str, suffix: &str) -> String {
    // `.bashrc` has no extension; an absurdly long "extension" is treated as stem.
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 && name.len() - i <= 32 => name.split_at(i),
        _ => (name, ""),
    };
    let room = MAX_NAME_BYTES.saturating_sub(suffix.len() + ext.len());
    let mut cut = stem.len().min(room);
    while !stem.is_char_boundary(cut) {
        cut -= 1;
    }
    format!("{}{suffix}{ext}", &stem[..cut])
}

/// Leading spaces and trailing dots/spaces are invisible or stripped by some file systems.
fn tidy(s: &str) -> &str {
    s.trim_start_matches(' ').trim_end_matches(['.', ' '])
}

fn is_invisible(c: char) -> bool {
    c.is_control() // C0, DEL, C1
        || matches!(c, '\u{200B}'..='\u{200F}' | '\u{202A}'..='\u{202E}' | '\u{2066}'..='\u{2069}' | '\u{FEFF}')
}

fn is_reserved(upper: &str) -> bool {
    matches!(upper, "CON" | "PRN" | "AUX" | "NUL")
        || ((upper.starts_with("COM") || upper.starts_with("LPT"))
            && upper.len() == 4
            && matches!(upper.as_bytes()[3], b'1'..=b'9'))
}

#[cfg(test)]
mod tests {
    use super::*;
    use rand::RngExt;

    #[test]
    fn examples() {
        let cases = [
            ("photo.jpg", "photo.jpg"),
            ("../../etc/passwd", "passwd"),
            ("..\\..\\win.ini", "win.ini"),
            ("dir/", "dir"),
            ("", "file"),
            (".", "file"),
            ("..", "file"),
            ("...", "file"),
            ("/", "file"),
            (".bashrc", ".bashrc"),
            ("  name. . ", "name"),
            ("a\u{0}b\u{1f}c\u{7f}", "abc"),
            ("invoice\u{202E}gpj.exe", "invoicegpj.exe"),
            ("zero\u{200B}width\u{FEFF}", "zerowidth"),
            ("\u{2066}x\u{2069}", "x"),
            ("a:b?.txt", "a_b_.txt"),
            ("CON", "_CON"),
            ("con.txt", "_con.txt"),
            ("Lpt9.tar.gz", "_Lpt9.tar.gz"),
            ("COM0", "COM0"),
            ("CONSOLE", "CONSOLE"),
            ("日本語.txt", "日本語.txt"),
        ];
        for (input, want) in cases {
            assert_eq!(file_name(input), want, "input {input:?}");
        }
    }

    #[test]
    fn long_names_keep_extension() {
        let long = format!("{}.jpeg", "é".repeat(300));
        let out = file_name(&long);
        assert!(out.len() <= MAX_NAME_BYTES && out.ends_with(".jpeg"), "{out}");
        assert!(out.len() >= MAX_NAME_BYTES - 1); // at most one byte lost to char boundary

        let numbered = numbered(&out, 12);
        assert!(numbered.len() <= MAX_NAME_BYTES && numbered.ends_with(" (12).jpeg"));
        assert_eq!(super::numbered("photo.jpg", 1), "photo (1).jpg");
        assert_eq!(super::numbered(".bashrc", 1), ".bashrc (1)");
    }

    #[test]
    fn random_names_are_safe() {
        let pool: Vec<char> = "a.Z /\\\u{0}\u{1f}\u{7f}\u{85}\u{200B}\u{202E}\u{2067}\u{FEFF}é日🦀:*\u{10FFFF}\u{D7FF}"
            .chars()
            .collect();
        let mut rng = rand::rng();
        for _ in 0..20_000 {
            let len = rng.random_range(0..400);
            let s: String = (0..len)
                .map(|_| {
                    if rng.random_bool(0.3) {
                        rng.random::<char>()
                    } else {
                        pool[rng.random_range(0..pool.len())]
                    }
                })
                .collect();
            let out = file_name(&s);
            assert!(!out.is_empty() && out != "." && out != "..", "{s:?} -> {out:?}");
            assert!(out.len() <= MAX_NAME_BYTES, "{s:?} -> {out:?}");
            assert!(!out.contains(['/', '\\']) && !out.chars().any(is_invisible), "{s:?} -> {out:?}");
            assert_eq!(file_name(&out), out, "not idempotent for {s:?}");
        }
    }
}
