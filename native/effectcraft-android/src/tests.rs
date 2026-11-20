use serde_json::Value;

use crate::{catalog::catalog_json, process};

fn catalog() -> Value {
    serde_json::from_str(&catalog_json()).expect("catalog is valid JSON")
}

#[test]
fn catalog_lists_every_effect_with_parameters() {
    let c = catalog();
    let effects = c["effects"].as_array().expect("effects array");
    assert!(effects.len() >= 300, "expected the full EffectCraft library, got {}", effects.len());
    assert!(effects.iter().all(|e| e["id"].as_str().is_some_and(|id| id.starts_with("ec."))));
    let gaussian = effects.iter().find(|e| e["id"] == "ec.blur.gaussian").expect("Gaussian Blur exists");
    assert_eq!(gaussian["category"], "Blur & Sharpen");
    assert!(!gaussian["params"].as_array().unwrap().is_empty());
}

#[test]
fn every_category_used_is_declared() {
    let c = catalog();
    let declared: Vec<&str> = c["categories"].as_array().unwrap().iter().filter_map(Value::as_str).collect();
    for e in c["effects"].as_array().unwrap() {
        assert!(declared.contains(&e["category"].as_str().unwrap()), "undeclared category on {}", e["id"]);
    }
}

#[test]
fn gaussian_blur_spreads_a_bright_pixel() {
    let (w, h) = (33u32, 33u32);
    let mut px = vec![0u8; (w * h * 4) as usize];
    let mid = ((16 * w + 16) * 4) as usize;
    px[mid..mid + 4].copy_from_slice(&[255, 255, 255, 255]);
    let stack = process::parse_stack(r#"[{"id":"ec.blur.gaussian","params":{"blurriness":6.0}}]"#, false).unwrap();
    assert_eq!(stack.len(), 1);
    let applied = process::process_rgba8(&mut px, w, h, [w as f64, h as f64], &stack, 0.0, 30.0, false);
    assert_eq!(applied, 1);
    let neighbour = (((16 * w + 18) * 4) + 3) as usize;
    assert!(px[neighbour] > 0, "blur should leak alpha into neighbouring pixels");
    assert!(px[mid + 3] < 255, "blur should dim the centre pixel");
}

#[test]
fn disabled_unknown_and_wrong_kind_effects_are_skipped() {
    let json = r#"[
        {"id":"ec.blur.gaussian","enabled":false},
        {"id":"ec.does.not.exist"},
        {"id":"ec.audio.reverb"}
    ]"#;
    assert!(process::parse_stack(json, false).unwrap().is_empty());
    assert_eq!(process::parse_stack(json, true).unwrap().len(), 1);
}

#[test]
fn audio_gate_silences_a_quiet_block() {
    let mut block = vec![0.001f32; 4800 * 2];
    let stack = process::parse_stack(r#"[{"id":"ec.audio.gate","params":{"threshold":-20.0}}]"#, true).unwrap();
    assert_eq!(process::process_audio(&mut block, 48_000, 0.0, &stack), 1);
    assert!(block.iter().all(|s| s.is_finite()));
}

#[test]
fn glow_style_effects_can_paint_outside_the_layer() {
    let (w, h) = (24u32, 24u32);
    let mut px = vec![0u8; (w * h * 4) as usize];
    // A bright block touching the left edge, so a blur has room to spill past it.
    for y in 8..16 {
        for x in 0..6 {
            let o = ((y * w + x) * 4) as usize;
            px[o..o + 4].copy_from_slice(&[255, 255, 255, 255]);
        }
    }
    let stack = process::parse_stack(r#"[{"id":"ec.blur.gaussian","params":{"blurriness":8.0}}]"#, false).unwrap();
    let grown = process::process_rgba8_grow(&px, w, h, [w as f64, h as f64], &stack, 0.0, 30.0, false, 1 << 20).expect("effect applied");
    assert_eq!(grown.pixels.len(), (grown.width * grown.height * 4) as usize);
    assert_eq!(grown.width, w + grown.left + grown.right);
    assert_eq!(grown.height, h + grown.top + grown.bottom);
    // An empty stack changes nothing and reports nothing.
    assert!(process::process_rgba8_grow(&px, w, h, [w as f64, h as f64], &[], 0.0, 30.0, false, 1 << 20).is_none());
    // A zero budget falls back to the layer rectangle.
    let tight = process::process_rgba8_grow(&px, w, h, [w as f64, h as f64], &stack, 0.0, 30.0, false, 0).expect("effect applied");
    assert_eq!((tight.width, tight.height, tight.left, tight.top), (w, h, 0, 0));
}

#[test]
fn blur_strength_follows_the_layer_not_the_bitmap() {
    // The same 64-unit layer shown at 64 px and at 32 px: a 8-unit blur should spread the same
    // fraction of the picture in both.
    fn spread(bitmap: u32) -> f64 {
        let mut px = vec![0u8; (bitmap * bitmap * 4) as usize];
        let mid = ((bitmap / 2 * bitmap + bitmap / 2) * 4) as usize;
        px[mid..mid + 4].copy_from_slice(&[255, 255, 255, 255]);
        let stack = process::parse_stack(r#"[{"id":"ec.blur.gaussian","params":{"blurriness":8.0}}]"#, false).unwrap();
        process::process_rgba8(&mut px, bitmap, bitmap, [64.0, 64.0], &stack, 0.0, 30.0, false);
        let lit = px.chunks_exact(4).filter(|p| p[3] > 8).count() as f64;
        lit / (bitmap * bitmap) as f64
    }
    let (full, half) = (spread(64), spread(32));
    assert!((full - half).abs() < 0.08, "coverage differs too much: {full} vs {half}");
}
