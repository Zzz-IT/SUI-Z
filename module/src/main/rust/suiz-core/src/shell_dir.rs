pub const LEGACY_SHELL_DIR_NAME: &str = "sui_shell";
pub const SHELL_DIR_PREFIX: &str = "sui_shell_";

pub fn is_valid_shell_dir_name(name: &str) -> bool {
    if name == LEGACY_SHELL_DIR_NAME {
        return true;
    }

    if !name.starts_with(SHELL_DIR_PREFIX) {
        return false;
    }

    name.bytes().all(|b| {
        b.is_ascii_lowercase()
            || b.is_ascii_digit()
            || b == b'_'
            || b == b'-'
    })
}

pub fn trim_marker(input: &str) -> &str {
    input.trim_matches(|c| c == ' ' || c == '\n' || c == '\r' || c == '\t')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_legacy() {
        assert!(is_valid_shell_dir_name("sui_shell"));
    }

    #[test]
    fn valid_randomized() {
        assert!(is_valid_shell_dir_name("sui_shell_abcdef0123456789"));
    }

    #[test]
    fn reject_path_traversal() {
        assert!(!is_valid_shell_dir_name("../sui_shell_x"));
        assert!(!is_valid_shell_dir_name("sui_shell_/bad"));
    }

    #[test]
    fn reject_uppercase() {
        assert!(!is_valid_shell_dir_name("sui_shell_ABC"));
    }
}
