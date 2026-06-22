pub fn parse_uid_list(input: &str) -> Vec<u32> {
    let mut values = input
        .lines()
        .filter_map(|line| line.trim().parse::<u32>().ok())
        .collect::<Vec<_>>();

    values.sort_unstable();
    values.dedup();
    values
}

pub fn encode_uid_list(values: &[u32]) -> String {
    let mut sorted = values.to_vec();
    sorted.sort_unstable();
    sorted.dedup();

    let mut out = String::new();
    for uid in sorted {
        out.push_str(&uid.to_string());
        out.push('\n');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_and_dedup() {
        assert_eq!(parse_uid_list("1000\n2000\n1000\nbad\n"), vec![1000, 2000]);
    }

    #[test]
    fn encode_sorted() {
        assert_eq!(encode_uid_list(&[2000, 1000, 1000]), "1000\n2000\n");
    }
}
