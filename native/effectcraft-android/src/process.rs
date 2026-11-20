//! Running an effect stack over an RGBA frame or an interleaved stereo audio block.

use effectcraft_effects::{Buf, EffectCtx, EffectEnv, EffectSpec, apply, audio_fx, find, lookup};
use effectcraft_raster::Image;
use serde_json::Value as Json;

use crate::convert::build_params;

/// One enabled effect of a stack, resolved against the registry.
pub struct Resolved {
    pub spec: &'static EffectSpec,
    pub overrides: Option<serde_json::Map<String, Json>>,
    pub seed: u32,
}

/// Parse the stack JSON: `[ { "id": "ec.blur.gaussian", "enabled": true, "seed": 1, "params": {..} } ]`.
/// Disabled and unknown effects are skipped. `audio` selects audio or picture effects.
pub fn parse_stack(json: &str, audio: bool) -> Result<Vec<Resolved>, String> {
    let root: Json = serde_json::from_str(json).map_err(|e| format!("invalid effect stack JSON: {e}"))?;
    let list = root.as_array().ok_or("effect stack must be a JSON array")?;
    let mut out = Vec::with_capacity(list.len());
    for (i, item) in list.iter().enumerate() {
        if item.get("enabled").and_then(Json::as_bool) == Some(false) {
            continue;
        }
        let Some(key) = item.get("id").and_then(Json::as_str) else { continue };
        let Some(spec) = find(key).or_else(|| lookup(key)) else { continue };
        if audio_fx::is_audio_effect(spec.id) != audio {
            continue;
        }
        let seed = item.get("seed").and_then(Json::as_u64).map(|s| s as u32).unwrap_or(i as u32 + 1);
        out.push(Resolved { spec, overrides: item.get("params").and_then(Json::as_object).cloned(), seed });
    }
    Ok(out)
}

/// Run the stack over a bitmap that shows a `layer_size` layer at `width` x `height` pixels.
/// Effects work in layer space (`Buf::scale` is pixels per layer unit), so the same project
/// looks the same in a small preview bitmap and in a full-resolution export. Returns the result
/// and the starting scale.
fn run_stack(px: &[u8], width: u32, height: u32, layer_size: [f64; 2], stack: &[Resolved], time: f64, frame_rate: f64, adjustment: bool) -> (Buf, f64) {
    let mut img = Image::new(width, height);
    for (dst, src) in img.data.iter_mut().zip(px.chunks_exact(4)) {
        *dst = [src[0] as f32 / 255.0, src[1] as f32 / 255.0, src[2] as f32 / 255.0, src[3] as f32 / 255.0];
    }
    let size = [layer_size[0].max(1.0), layer_size[1].max(1.0)];
    let base = width as f64 / size[0];
    let mut buf = Buf { img, offset: [0.0, 0.0], scale: base };
    for (index, fx) in stack.iter().enumerate() {
        let params = build_params(fx.spec, fx.overrides.as_ref(), size);
        let env = EffectEnv { comp_time: time, frame_rate, effect_index: index, ..EffectEnv::default() };
        let ctx = EffectCtx { params: &params, time, layer_size: size, seed: fx.seed, adjustment, env };
        buf = apply(fx.spec, &ctx, buf);
    }
    (buf, base)
}

/// Premultiplied 8-bit pixel from a float pixel. Colour can never exceed alpha in a bitmap.
fn to_rgba8(p: [f32; 4]) -> [u8; 4] {
    let a = p[3].clamp(0.0, 1.0);
    let c = |v: f32| (v.clamp(0.0, a) * 255.0 + 0.5) as u8;
    [c(p[0]), c(p[1]), c(p[2]), (a * 255.0 + 0.5) as u8]
}

/// Process premultiplied RGBA8 pixels in place, keeping the source rectangle. Returns the number of
/// effects applied.
pub fn process_rgba8(px: &mut [u8], width: u32, height: u32, layer_size: [f64; 2], stack: &[Resolved], time: f64, frame_rate: f64, adjustment: bool) -> usize {
    let n = width as usize * height as usize;
    if stack.is_empty() || width == 0 || height == 0 || px.len() < n * 4 {
        return 0;
    }
    let (buf, base) = run_stack(px, width, height, layer_size, stack, time, frame_rate, adjustment);
    // Read the source rectangle back out of the (possibly grown) buffer.
    let (ox, oy) = (buf.offset[0], buf.offset[1]);
    let scale = if buf.scale.is_finite() && buf.scale > 0.0 { buf.scale / base } else { 1.0 };
    for y in 0..height as usize {
        for x in 0..width as usize {
            let sx = (x as f64 * scale + ox).round() as i64;
            let sy = (y as f64 * scale + oy).round() as i64;
            let o = (y * width as usize + x) * 4;
            px[o..o + 4].copy_from_slice(&to_rgba8(buf.img.get(sx, sy)));
        }
    }
    stack.len()
}

/// The result of a stack that may paint outside the layer rectangle (glow, shadows, motion tile).
/// Margins are in pixels of the source bitmap: the layer rectangle sits at (`left`, `top`) inside
/// the `width` x `height` result.
pub struct Grown {
    pub width: u32,
    pub height: u32,
    pub left: u32,
    pub top: u32,
    pub right: u32,
    pub bottom: u32,
    pub pixels: Vec<u8>,
}

/// Like [`process_rgba8`] but keeps everything the effects painted, up to `max_pixels` in total.
/// Past the budget (or when an effect rescaled the buffer) the result is the layer rectangle only.
pub fn process_rgba8_grow(px: &[u8], width: u32, height: u32, layer_size: [f64; 2], stack: &[Resolved], time: f64, frame_rate: f64, adjustment: bool, max_pixels: u64) -> Option<Grown> {
    let n = width as usize * height as usize;
    if stack.is_empty() || width == 0 || height == 0 || px.len() < n * 4 {
        return None;
    }
    let (buf, base) = run_stack(px, width, height, layer_size, stack, time, frame_rate, adjustment);
    let unit = ((buf.scale / base) - 1.0).abs() < 1e-6 && buf.offset[0].is_finite() && buf.offset[1].is_finite();
    let (ox, oy) = (buf.offset[0].round() as i64, buf.offset[1].round() as i64);
    let (bw, bh) = (buf.img.width as i64, buf.img.height as i64);
    // Output canvas in layer coordinates: the union of the layer rectangle and the buffer.
    let left = if unit { ox.max(0) } else { 0 };
    let top = if unit { oy.max(0) } else { 0 };
    let right = if unit { (bw - ox - width as i64).max(0) } else { 0 };
    let bottom = if unit { (bh - oy - height as i64).max(0) } else { 0 };
    let (w, h) = (width as i64 + left + right, height as i64 + top + bottom);
    let (left, top, right, bottom, w, h) = if (w as u64) * (h as u64) > max_pixels.max(n as u64) {
        (0, 0, 0, 0, width as i64, height as i64)
    } else {
        (left, top, right, bottom, w, h)
    };
    let mut pixels = vec![0u8; (w * h * 4) as usize];
    let scale = if buf.scale.is_finite() && buf.scale > 0.0 { buf.scale / base } else { 1.0 };
    for y in 0..h {
        for x in 0..w {
            // Output (x, y) is layer pixel (x - left, y - top).
            let (lx, ly) = ((x - left) as f64, (y - top) as f64);
            let p = buf.img.get((lx * scale + buf.offset[0]).round() as i64, (ly * scale + buf.offset[1]).round() as i64);
            let o = ((y * w + x) * 4) as usize;
            pixels[o..o + 4].copy_from_slice(&to_rgba8(p));
        }
    }
    Some(Grown { width: w as u32, height: h as u32, left: left as u32, top: top as u32, right: right as u32, bottom: bottom as u32, pixels })
}

/// Longest pre-roll (seconds) any effect in the stack wants before an audio block.
pub fn audio_preroll(stack: &[Resolved]) -> f64 {
    stack
        .iter()
        .map(|fx| {
            let params = build_params(fx.spec, fx.overrides.as_ref(), [1.0, 1.0]);
            audio_fx::preroll(fx.spec.id, &params)
        })
        .fold(0.0, f64::max)
}

/// Run the audio effects over interleaved stereo `samples` starting at `start` seconds.
pub fn process_audio(samples: &mut [f32], rate: u32, start: f64, stack: &[Resolved]) -> usize {
    for fx in stack {
        let params = build_params(fx.spec, fx.overrides.as_ref(), [1.0, 1.0]);
        audio_fx::process(fx.spec.id, &params, samples, rate, start);
    }
    stack.len()
}
