use std::borrow::Cow;
use std::f64::consts::PI;

const MAX_INPUT_BYTES: usize = 4096;

/// Resolves a literal CSS color to Android ARGB. Cascade-dependent values
/// such as currentColor and inherit are left for the caller.
pub(crate) fn parse_color(raw: &str) -> Option<u32> {
    if raw.len() > MAX_INPUT_BYTES {
        return None;
    }

    let trimmed = trim_value(raw);
    let value = if trimmed.chars().any(char::is_uppercase) {
        Cow::Owned(trimmed.to_lowercase())
    } else {
        Cow::Borrowed(trimmed)
    };

    if let Some(digits) = value.strip_prefix('#') {
        return parse_hex(digits);
    }
    if let Some(color) = named_color(&value) {
        return Some(color);
    }

    let (function, arguments) = value.split_once('(')?;
    let arguments = arguments.strip_suffix(')')?;
    if arguments.contains(['(', ')']) {
        return None;
    }
    match function {
        "rgb" | "rgba" => parse_rgb(Components::parse(arguments)?),
        "hsl" | "hsla" => parse_hsl(Components::parse(arguments)?),
        _ => None,
    }
}

fn parse_hex(digits: &str) -> Option<u32> {
    let mut nibbles = [0; 8];
    let mut length = 0;
    for digit in digits.chars() {
        *nibbles.get_mut(length)? = hex_digit(digit)?;
        length += 1;
    }
    let [a, b, c, d, e, f, g, h] = nibbles;
    let expand = |n| n * 17;
    let pair = |high, low| high * 16 + low;
    let rgba = match length {
        3 => [expand(a), expand(b), expand(c), 255],
        4 => [expand(a), expand(b), expand(c), expand(d)],
        6 => [pair(a, b), pair(c, d), pair(e, f), 255],
        8 => [pair(a, b), pair(c, d), pair(e, f), pair(g, h)],
        _ => return None,
    };
    let [red, green, blue, alpha] = rgba;
    Some(u32::from_be_bytes([alpha, red, green, blue]))
}

pub(crate) fn hex_digit(digit: char) -> Option<u8> {
    match digit {
        '0'..='9' => Some(digit as u8 - b'0'),
        'a'..='f' => Some(digit as u8 - b'a' + 10),
        'ａ'..='ｆ' => Some((digit as u32 - 'ａ' as u32) as u8 + 10),
        _ => {
            // The former JVM parser accepted Character.digit's BMP decimal
            // digits in hex colors. Keep this compatibility for old books.
            const DECIMAL_ZEROS: &[u32] = &[
                0x660, 0x6f0, 0x7c0, 0x966, 0x9e6, 0xa66, 0xae6, 0xb66, 0xbe6, 0xc66, 0xce6, 0xd66,
                0xde6, 0xe50, 0xed0, 0xf20, 0x1040, 0x1090, 0x17e0, 0x1810, 0x1946, 0x19d0, 0x1a80,
                0x1a90, 0x1b50, 0x1bb0, 0x1c40, 0x1c50, 0xa620, 0xa8d0, 0xa900, 0xa9d0, 0xa9f0,
                0xaa50, 0xabf0, 0xff10,
            ];
            DECIMAL_ZEROS.iter().find_map(|&zero| {
                let offset = (digit as u32).wrapping_sub(zero);
                (offset <= 9).then_some(offset as u8)
            })
        }
    }
}

struct Components<'a> {
    channels: [&'a str; 3],
    alpha: Option<&'a str>,
    legacy: bool,
}

impl<'a> Components<'a> {
    fn parse(arguments: &'a str) -> Option<Self> {
        if arguments.contains(',') {
            if arguments.contains('/') {
                return None;
            }
            let mut parts = arguments.split(',').map(trim_value);
            let channels = [parts.next()?, parts.next()?, parts.next()?];
            let alpha = parts.next();
            if parts.next().is_some() || channels.contains(&"") || alpha == Some("") {
                return None;
            }
            return Some(Self {
                channels,
                alpha,
                legacy: true,
            });
        }

        let (body, alpha) = if let Some((body, alpha)) = arguments.split_once('/') {
            let mut tokens = whitespace_tokens(alpha);
            let alpha = tokens.next()?;
            if tokens.next().is_some() {
                return None;
            }
            (body, Some(alpha))
        } else {
            (arguments, None)
        };
        let mut tokens = whitespace_tokens(body);
        let channels = [tokens.next()?, tokens.next()?, tokens.next()?];
        if tokens.next().is_some() {
            return None;
        }
        Some(Self {
            channels,
            alpha,
            legacy: false,
        })
    }

    fn alpha_byte(&self) -> Option<u8> {
        let Some(alpha) = self.alpha else {
            return Some(255);
        };
        let opacity = match alpha.strip_suffix('%') {
            Some(percent) => parse_number(percent)? / 100.0,
            None => parse_number(alpha)?,
        };
        Some((opacity.clamp(0.0, 1.0) * 255.0).round() as u8)
    }
}

fn parse_rgb(components: Components<'_>) -> Option<u32> {
    let [red, green, blue] = components.channels;
    let percentage = red.ends_with('%');
    if components.legacy
        && (green.ends_with('%') != percentage || blue.ends_with('%') != percentage)
    {
        return None;
    }

    Some(u32::from_be_bytes([
        components.alpha_byte()?,
        rgb_channel(red)?,
        rgb_channel(green)?,
        rgb_channel(blue)?,
    ]))
}

fn rgb_channel(value: &str) -> Option<u8> {
    let numeric = match value.strip_suffix('%') {
        Some(percent) => (parse_number(percent)? / 100.0) * 255.0,
        None => parse_number(value)?,
    };
    Some(channel_byte(numeric))
}

fn parse_hsl(components: Components<'_>) -> Option<u32> {
    let [hue, saturation, lightness] = components.channels;
    let hue_sector = ((parse_hue(hue)? % 360.0 + 360.0) % 360.0) / 60.0;
    let saturation = hsl_channel(saturation, components.legacy)?;
    let lightness = hsl_channel(lightness, components.legacy)?;
    let chroma = (1.0 - (2.0 * lightness - 1.0).abs()) * saturation;
    let secondary = chroma * (1.0 - (hue_sector % 2.0 - 1.0).abs());
    let [red, green, blue] = match hue_sector {
        hue if hue < 1.0 => [chroma, secondary, 0.0],
        hue if hue < 2.0 => [secondary, chroma, 0.0],
        hue if hue < 3.0 => [0.0, chroma, secondary],
        hue if hue < 4.0 => [0.0, secondary, chroma],
        hue if hue < 5.0 => [secondary, 0.0, chroma],
        _ => [chroma, 0.0, secondary],
    };
    let offset = lightness - chroma / 2.0;
    Some(u32::from_be_bytes([
        components.alpha_byte()?,
        channel_byte((red + offset) * 255.0),
        channel_byte((green + offset) * 255.0),
        channel_byte((blue + offset) * 255.0),
    ]))
}

fn hsl_channel(value: &str, legacy: bool) -> Option<f64> {
    let number = match value.strip_suffix('%') {
        Some(percent) => parse_number(percent)?,
        None if !legacy => parse_number(value)?,
        None => return None,
    };
    Some((number / 100.0).clamp(0.0, 1.0))
}

fn parse_hue(value: &str) -> Option<f64> {
    let angle = if let Some(degrees) = value.strip_suffix("deg") {
        parse_number(degrees)?
    } else if let Some(gradians) = value.strip_suffix("grad") {
        parse_number(gradians)? * 0.9
    } else if let Some(radians) = value.strip_suffix("rad") {
        parse_number(radians)? * 180.0 / PI
    } else if let Some(turns) = value.strip_suffix("turn") {
        parse_number(turns)? * 360.0
    } else {
        parse_number(value)?
    };
    angle.is_finite().then_some(angle)
}

fn parse_number(value: &str) -> Option<f64> {
    let number = value.parse::<f64>().ok()?;
    number.is_finite().then_some(number)
}

fn channel_byte(value: f64) -> u8 {
    value.clamp(0.0, 255.0).round() as u8
}

fn whitespace_tokens(value: &str) -> impl Iterator<Item = &str> {
    trim_value(value)
        .split([' ', '\t', '\n', '\x0b', '\x0c', '\r'])
        .filter(|piece| !piece.is_empty())
}

fn trim_value(value: &str) -> &str {
    // Kotlin trim accepts JVM whitespace at the ends. Inside modern
    // channel lists, the old CSS whitespace regex only accepts ASCII.
    value.trim_matches(|ch: char| {
        ch.is_whitespace() && ch != '\u{0085}' || matches!(ch, '\u{001c}'..='\u{001f}')
    })
}

fn named_color(value: &str) -> Option<u32> {
    Some(match value {
        "aliceblue" => 0xfff0_f8ff,
        "antiquewhite" => 0xfffa_ebd7,
        "aqua" => 0xff00_ffff,
        "aquamarine" => 0xff7f_ffd4,
        "azure" => 0xfff0_ffff,
        "beige" => 0xfff5_f5dc,
        "bisque" => 0xffff_e4c4,
        "black" => 0xff00_0000,
        "blanchedalmond" => 0xffff_ebcd,
        "blue" => 0xff00_00ff,
        "blueviolet" => 0xff8a_2be2,
        "brown" => 0xffa5_2a2a,
        "burlywood" => 0xffde_b887,
        "cadetblue" => 0xff5f_9ea0,
        "chartreuse" => 0xff7f_ff00,
        "chocolate" => 0xffd2_691e,
        "coral" => 0xffff_7f50,
        "cornflowerblue" => 0xff64_95ed,
        "cornsilk" => 0xffff_f8dc,
        "crimson" => 0xffdc_143c,
        "cyan" => 0xff00_ffff,
        "darkblue" => 0xff00_008b,
        "darkcyan" => 0xff00_8b8b,
        "darkgoldenrod" => 0xffb8_860b,
        "darkgray" => 0xffa9_a9a9,
        "darkgreen" => 0xff00_6400,
        "darkgrey" => 0xffa9_a9a9,
        "darkkhaki" => 0xffbd_b76b,
        "darkmagenta" => 0xff8b_008b,
        "darkolivegreen" => 0xff55_6b2f,
        "darkorange" => 0xffff_8c00,
        "darkorchid" => 0xff99_32cc,
        "darkred" => 0xff8b_0000,
        "darksalmon" => 0xffe9_967a,
        "darkseagreen" => 0xff8f_bc8f,
        "darkslateblue" => 0xff48_3d8b,
        "darkslategray" => 0xff2f_4f4f,
        "darkslategrey" => 0xff2f_4f4f,
        "darkturquoise" => 0xff00_ced1,
        "darkviolet" => 0xff94_00d3,
        "deeppink" => 0xffff_1493,
        "deepskyblue" => 0xff00_bfff,
        "dimgray" => 0xff69_6969,
        "dimgrey" => 0xff69_6969,
        "dodgerblue" => 0xff1e_90ff,
        "firebrick" => 0xffb2_2222,
        "floralwhite" => 0xffff_faf0,
        "forestgreen" => 0xff22_8b22,
        "fuchsia" => 0xffff_00ff,
        "gainsboro" => 0xffdc_dcdc,
        "ghostwhite" => 0xfff8_f8ff,
        "gold" => 0xffff_d700,
        "goldenrod" => 0xffda_a520,
        "gray" => 0xff80_8080,
        "green" => 0xff00_8000,
        "greenyellow" => 0xffad_ff2f,
        "grey" => 0xff80_8080,
        "honeydew" => 0xfff0_fff0,
        "hotpink" => 0xffff_69b4,
        "indianred" => 0xffcd_5c5c,
        "indigo" => 0xff4b_0082,
        "ivory" => 0xffff_fff0,
        "khaki" => 0xfff0_e68c,
        "lavender" => 0xffe6_e6fa,
        "lavenderblush" => 0xffff_f0f5,
        "lawngreen" => 0xff7c_fc00,
        "lemonchiffon" => 0xffff_facd,
        "lightblue" => 0xffad_d8e6,
        "lightcoral" => 0xfff0_8080,
        "lightcyan" => 0xffe0_ffff,
        "lightgoldenrodyellow" => 0xfffa_fad2,
        "lightgray" => 0xffd3_d3d3,
        "lightgreen" => 0xff90_ee90,
        "lightgrey" => 0xffd3_d3d3,
        "lightpink" => 0xffff_b6c1,
        "lightsalmon" => 0xffff_a07a,
        "lightseagreen" => 0xff20_b2aa,
        "lightskyblue" => 0xff87_cefa,
        "lightslategray" => 0xff77_8899,
        "lightslategrey" => 0xff77_8899,
        "lightsteelblue" => 0xffb0_c4de,
        "lightyellow" => 0xffff_ffe0,
        "lime" => 0xff00_ff00,
        "limegreen" => 0xff32_cd32,
        "linen" => 0xfffa_f0e6,
        "magenta" => 0xffff_00ff,
        "maroon" => 0xff80_0000,
        "mediumaquamarine" => 0xff66_cdaa,
        "mediumblue" => 0xff00_00cd,
        "mediumorchid" => 0xffba_55d3,
        "mediumpurple" => 0xff93_70db,
        "mediumseagreen" => 0xff3c_b371,
        "mediumslateblue" => 0xff7b_68ee,
        "mediumspringgreen" => 0xff00_fa9a,
        "mediumturquoise" => 0xff48_d1cc,
        "mediumvioletred" => 0xffc7_1585,
        "midnightblue" => 0xff19_1970,
        "mintcream" => 0xfff5_fffa,
        "mistyrose" => 0xffff_e4e1,
        "moccasin" => 0xffff_e4b5,
        "navajowhite" => 0xffff_dead,
        "navy" => 0xff00_0080,
        "oldlace" => 0xfffd_f5e6,
        "olive" => 0xff80_8000,
        "olivedrab" => 0xff6b_8e23,
        "orange" => 0xffff_a500,
        "orangered" => 0xffff_4500,
        "orchid" => 0xffda_70d6,
        "palegoldenrod" => 0xffee_e8aa,
        "palegreen" => 0xff98_fb98,
        "paleturquoise" => 0xffaf_eeee,
        "palevioletred" => 0xffdb_7093,
        "papayawhip" => 0xffff_efd5,
        "peachpuff" => 0xffff_dab9,
        "peru" => 0xffcd_853f,
        "pink" => 0xffff_c0cb,
        "plum" => 0xffdd_a0dd,
        "powderblue" => 0xffb0_e0e6,
        "purple" => 0xff80_0080,
        "rebeccapurple" => 0xff66_3399,
        "red" => 0xffff_0000,
        "rosybrown" => 0xffbc_8f8f,
        "royalblue" => 0xff41_69e1,
        "saddlebrown" => 0xff8b_4513,
        "salmon" => 0xfffa_8072,
        "sandybrown" => 0xfff4_a460,
        "seagreen" => 0xff2e_8b57,
        "seashell" => 0xffff_f5ee,
        "sienna" => 0xffa0_522d,
        "silver" => 0xffc0_c0c0,
        "skyblue" => 0xff87_ceeb,
        "slateblue" => 0xff6a_5acd,
        "slategray" => 0xff70_8090,
        "slategrey" => 0xff70_8090,
        "snow" => 0xffff_fafa,
        "springgreen" => 0xff00_ff7f,
        "steelblue" => 0xff46_82b4,
        "tan" => 0xffd2_b48c,
        "teal" => 0xff00_8080,
        "thistle" => 0xffd8_bfd8,
        "tomato" => 0xffff_6347,
        "transparent" => 0x0000_0000,
        "turquoise" => 0xff40_e0d0,
        "violet" => 0xffee_82ee,
        "wheat" => 0xfff5_deb3,
        "white" => 0xffff_ffff,
        "whitesmoke" => 0xfff5_f5f5,
        "yellow" => 0xffff_ff00,
        "yellowgreen" => 0xff9a_cd32,
        _ => return None,
    })
}
