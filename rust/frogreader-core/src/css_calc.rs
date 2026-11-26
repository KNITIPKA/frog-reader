const MAX_EXPRESSION_BYTES: usize = 64 * 1024;
const MAX_NESTING: usize = 64;

/// Evaluate one CSS math value in the caller's requested output units.
pub(crate) fn evaluate(
    value: &str,
    em: f32,
    rem: f32,
    px: f32,
    percent: Option<f32>,
    accept_scalar: bool,
) -> Option<f32> {
    if value.len() > MAX_EXPRESSION_BYTES {
        return None;
    }
    let mut parser = Parser {
        remaining: value,
        em,
        rem,
        px,
        percent,
    };
    let value = parser.factor(0)?;
    parser.skip_whitespace();
    if !parser.remaining.is_empty() || (!value.length && !accept_scalar && value.number != 0.0) {
        return None;
    }
    (!value.number.is_nan()).then_some(value.number)
}

struct Value {
    number: f32,
    length: bool,
}

impl Value {
    fn compatible(&self, other: &Self) -> Option<()> {
        (self.length == other.length).then_some(())
    }
}

struct Parser<'a> {
    remaining: &'a str,
    em: f32,
    rem: f32,
    px: f32,
    percent: Option<f32>,
}

impl<'a> Parser<'a> {
    fn take_ascii(&mut self, predicate: impl Fn(u8) -> bool) -> &'a str {
        let end = self
            .remaining
            .bytes()
            .take_while(|byte| predicate(*byte))
            .count();
        let (taken, remaining) = self.remaining.split_at(end);
        self.remaining = remaining;
        taken
    }

    fn skip_whitespace(&mut self) -> bool {
        let rest = self.remaining.trim_start_matches(kotlin_whitespace);
        let skipped = rest.len() < self.remaining.len();
        self.remaining = rest;
        skipped
    }

    fn consume(&mut self, character: char) -> bool {
        if let Some(rest) = self.remaining.strip_prefix(character) {
            self.remaining = rest;
            true
        } else {
            false
        }
    }

    fn expression(&mut self, depth: usize) -> Option<Value> {
        let mut left = self.term(depth)?;
        loop {
            let previous = self.remaining;
            if !self.skip_whitespace() {
                return Some(left);
            }
            let subtract = match self.remaining.as_bytes().first() {
                Some(b'+') => false,
                Some(b'-') => true,
                _ => {
                    self.remaining = previous;
                    return Some(left);
                }
            };
            self.remaining = &self.remaining[1..];
            // CSS treats a sign next to a number as part of that number.
            if !self.skip_whitespace() {
                return None;
            }
            let right = self.term(depth)?;
            left.compatible(&right)?;
            left.number = if subtract {
                left.number - right.number
            } else {
                left.number + right.number
            };
        }
    }

    fn term(&mut self, depth: usize) -> Option<Value> {
        let mut left = self.factor(depth)?;
        loop {
            let previous = self.remaining;
            self.skip_whitespace();
            if self.consume('*') {
                let right = self.factor(depth)?;
                if left.length && right.length {
                    return None;
                }
                left.number *= right.number;
                left.length |= right.length;
            } else if self.consume('/') {
                let right = self.factor(depth)?;
                if right.length || right.number == 0.0 {
                    return None;
                }
                left.number /= right.number;
            } else {
                self.remaining = previous;
                return Some(left);
            }
        }
    }

    fn factor(&mut self, depth: usize) -> Option<Value> {
        if depth > MAX_NESTING {
            return None;
        }
        self.skip_whitespace();
        if self.consume('(') {
            let value = self.expression(depth + 1)?;
            self.skip_whitespace();
            return self.consume(')').then_some(value);
        }
        if self
            .remaining
            .as_bytes()
            .first()
            .is_some_and(u8::is_ascii_alphabetic)
        {
            self.function(depth + 1)
        } else {
            self.number()
        }
    }

    fn function(&mut self, depth: usize) -> Option<Value> {
        let name = self.take_ascii(|byte| byte.is_ascii_alphabetic());
        if name.len() > 5 {
            return None;
        }
        let name = name.to_ascii_lowercase();
        self.skip_whitespace();
        if !self.consume('(') {
            return None;
        }
        let mut result = self.expression(depth)?;
        self.skip_whitespace();
        match name.as_str() {
            "calc" => {}
            "min" | "max" => {
                let minimum = name == "min";
                while self.consume(',') {
                    let next = self.expression(depth)?;
                    result.compatible(&next)?;
                    result.number = extreme(result.number, next.number, minimum);
                    self.skip_whitespace();
                }
            }
            "clamp" => {
                let preferred = self.function_argument(depth, &result)?;
                let upper = self.function_argument(depth, &result)?;
                let bounded = extreme(preferred.number, upper.number, true);
                result.number = extreme(result.number, bounded, false);
            }
            _ => return None,
        }
        self.consume(')').then_some(result)
    }

    fn function_argument(&mut self, depth: usize, first: &Value) -> Option<Value> {
        if !self.consume(',') {
            return None;
        }
        let argument = self.expression(depth)?;
        first.compatible(&argument)?;
        self.skip_whitespace();
        Some(argument)
    }

    fn number(&mut self) -> Option<Value> {
        let start = self.remaining;
        if !self.consume('-') {
            self.consume('+');
        }
        let whole = self.take_ascii(|byte| byte.is_ascii_digit());
        let fractional = if self.consume('.') {
            self.take_ascii(|byte| byte.is_ascii_digit())
        } else {
            ""
        };
        if whole.is_empty() && fractional.is_empty() {
            return None;
        }
        let number = start[..start.len() - self.remaining.len()]
            .parse::<f32>()
            .ok()?;
        let unit = self.take_ascii(|byte| byte.is_ascii_alphabetic());
        if unit.is_empty() && !self.consume('%') {
            return Some(Value {
                number,
                length: false,
            });
        }
        if unit.len() > 4 {
            return None;
        }
        // Keep conversion operations in their original f32 order.
        let length = match unit.to_ascii_lowercase().as_str() {
            "" => number * self.percent?,
            "em" => number * self.em,
            "rem" => number * self.rem,
            "px" => number * self.px,
            "pt" => number * self.px * (4.0 / 3.0),
            "pc" => number * self.px * 16.0,
            "in" => number * self.px * 96.0,
            "cm" => number * self.px * 37.795,
            "mm" => number * self.px * 3.7795,
            "q" => number * self.px * 0.94488,
            "ch" | "ex" => number * self.em * 0.5,
            "vw" | "vmin" => number * self.rem * 0.30,
            "vh" | "vmax" => number * self.rem * 0.50,
            _ => return None,
        };
        Some(Value {
            number: length,
            length: true,
        })
    }
}

fn kotlin_whitespace(character: char) -> bool {
    (character.is_whitespace() && character != '\u{0085}')
        || ('\u{001c}'..='\u{001f}').contains(&character)
}

/// Match Java Math.min/max for signed zero and NaN as well as finite numbers.
fn extreme(left: f32, right: f32, minimum: bool) -> f32 {
    if left.is_nan() || right.is_nan() {
        return f32::NAN;
    }
    if left == 0.0 && right == 0.0 {
        return f32::from_bits(if minimum {
            left.to_bits() | right.to_bits()
        } else {
            left.to_bits() & right.to_bits()
        });
    }
    if minimum {
        left.min(right)
    } else {
        left.max(right)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn calc(text: &str) -> Option<f32> {
        evaluate(text, 1.0, 1.0, 1.0, None, false)
    }

    #[test]
    fn bounds_nested_functions_and_parentheses() {
        let value = "(".repeat(MAX_NESTING - 1) + "1px" + &")".repeat(MAX_NESTING - 1);
        assert_eq!(calc(&format!("calc({value})")), Some(1.0));
        assert_eq!(calc(&format!("calc(({value}))")), None);
    }

    #[test]
    fn bounds_expression_bytes_before_scanning() {
        let value = " ".repeat(MAX_EXPRESSION_BYTES - 1) + "0";
        assert_eq!(calc(&value), Some(0.0));
        assert_eq!(calc(&(value + " ")), None);
    }
}
