//! Kinetic's EffectCraft bridge.
//!
//! Kinetic owns the editor, timeline, preview and keyframes. This library exposes the EffectCraft
//! effects library to it through a small JNI surface:
//!
//! * [`catalog::catalog_json`] - every effect, its category and typed parameters.
//! * [`process::process_rgba8`] - run an effect stack over a frame (clip, shape, text, or the whole
//!   composition for adjustment-style effects).
//! * [`process::process_audio`] - run the audio effects over an interleaved stereo block.
//!
//! Parameter values are evaluated by Kinetic (its keyframes, expressions and easing) and passed in
//! per call, so animation stays in the editor and the engine stays stateless.

pub mod catalog;
pub mod convert;
pub mod process;

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use std::cell::RefCell;

use jni::objects::{JByteBuffer, JClass, JFloatArray, JIntArray, JString};
use jni::sys::{jboolean, jdouble, jint, jstring};

thread_local! {
    /// The grown result of the last `nativeProcessImageGrow` call on this thread, waiting for
    /// `nativeTakeGrown`. Both calls must run on the same Java thread.
    static GROWN: RefCell<Option<process::Grown>> = const { RefCell::new(None) };
}

const OK_VERSION: &str = concat!("kinetic-effectcraft ", env!("CARGO_PKG_VERSION"));

fn java_string(env: &mut JNIEnv<'_>, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn read_string(env: &mut JNIEnv<'_>, s: &JString<'_>) -> Option<String> {
    env.get_string(s).ok().map(|j| j.to_string_lossy().into_owned())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftNative_nativeVersion<'l>(mut env: JNIEnv<'l>, _class: JClass<'l>) -> jstring {
    java_string(&mut env, OK_VERSION)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftNative_nativeCatalogJson<'l>(mut env: JNIEnv<'l>, _class: JClass<'l>) -> jstring {
    match catch_unwind(catalog::catalog_json) {
        Ok(json) => java_string(&mut env, &json),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Apply a picture-effect stack to a direct `ByteBuffer` of premultiplied RGBA8 pixels, in place.
/// Returns the number of effects applied, or a negative error code:
/// -1 bad arguments, -2 bad stack JSON, -3 internal panic.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftNative_nativeProcessImage<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    buffer: JByteBuffer<'l>,
    width: jint,
    height: jint,
    stack_json: JString<'l>,
    time: jdouble,
    frame_rate: jdouble,
    adjustment: jboolean,
) -> jint {
    if width <= 0 || height <= 0 {
        return -1;
    }
    let Some(json) = read_string(&mut env, &stack_json) else { return -1 };
    let Ok(ptr) = env.get_direct_buffer_address(&buffer) else { return -1 };
    let Ok(capacity) = env.get_direct_buffer_capacity(&buffer) else { return -1 };
    let needed = width as usize * height as usize * 4;
    if ptr.is_null() || capacity < needed {
        return -1;
    }
    let stack = match process::parse_stack(&json, false) {
        Ok(stack) => stack,
        Err(_) => return -2,
    };
    // SAFETY: the buffer is a live direct ByteBuffer of at least `needed` bytes that Kotlin does
    // not touch for the duration of this call.
    let pixels = unsafe { std::slice::from_raw_parts_mut(ptr, needed) };
    match catch_unwind(AssertUnwindSafe(|| {
        process::process_rgba8(pixels, width as u32, height as u32, &stack, time, frame_rate, adjustment != 0)
    })) {
        Ok(n) => n as jint,
        Err(_) => -3,
    }
}

/// Like `nativeProcessImage`, but keeps what the effects painted outside the layer rectangle.
/// The source buffer is left untouched. On success `out` receives
/// `[width, height, left, top, right, bottom]` of the result (margins in source pixels) and the
/// pixels wait for `nativeTakeGrown` on this same thread. Returns the number of effects applied,
/// or a negative error code.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftNative_nativeProcessImageGrow<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    buffer: JByteBuffer<'l>,
    width: jint,
    height: jint,
    stack_json: JString<'l>,
    time: jdouble,
    frame_rate: jdouble,
    adjustment: jboolean,
    max_pixels: jint,
    out: JIntArray<'l>,
) -> jint {
    if width <= 0 || height <= 0 {
        return -1;
    }
    let Some(json) = read_string(&mut env, &stack_json) else { return -1 };
    let Ok(ptr) = env.get_direct_buffer_address(&buffer) else { return -1 };
    let Ok(capacity) = env.get_direct_buffer_capacity(&buffer) else { return -1 };
    let needed = width as usize * height as usize * 4;
    if ptr.is_null() || capacity < needed {
        return -1;
    }
    let stack = match process::parse_stack(&json, false) {
        Ok(stack) => stack,
        Err(_) => return -2,
    };
    // SAFETY: a live direct ByteBuffer of at least `needed` bytes, read-only for this call.
    let pixels = unsafe { std::slice::from_raw_parts(ptr as *const u8, needed) };
    let grown = match catch_unwind(AssertUnwindSafe(|| {
        process::process_rgba8_grow(pixels, width as u32, height as u32, &stack, time, frame_rate, adjustment != 0, max_pixels.max(0) as u64)
    })) {
        Ok(Some(grown)) => grown,
        Ok(None) => return 0,
        Err(_) => return -3,
    };
    let info = [grown.width as i32, grown.height as i32, grown.left as i32, grown.top as i32, grown.right as i32, grown.bottom as i32];
    if env.set_int_array_region(&out, 0, &info).is_err() {
        return -1;
    }
    GROWN.with(|slot| *slot.borrow_mut() = Some(grown));
    stack.len() as jint
}

/// Copies the pending result of `nativeProcessImageGrow` into `buffer` (premultiplied RGBA8) and
/// clears it. Returns the bytes written, or -1 when nothing is pending or the buffer is too small.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftNative_nativeTakeGrown<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    buffer: JByteBuffer<'l>,
) -> jint {
    let Ok(ptr) = env.get_direct_buffer_address(&buffer) else { return -1 };
    let Ok(capacity) = env.get_direct_buffer_capacity(&buffer) else { return -1 };
    let grown = GROWN.with(|slot| slot.borrow_mut().take());
    let Some(grown) = grown else { return -1 };
    if ptr.is_null() || capacity < grown.pixels.len() {
        return -1;
    }
    // SAFETY: `ptr` is a live direct buffer of at least `pixels.len()` bytes.
    unsafe { std::ptr::copy_nonoverlapping(grown.pixels.as_ptr(), ptr, grown.pixels.len()) };
    grown.pixels.len() as jint
}

/// Seconds of audio an audio-effect stack wants fed in before a block (0 when unknown).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftNative_nativeAudioPreroll<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    stack_json: JString<'l>,
) -> jdouble {
    let Some(json) = read_string(&mut env, &stack_json) else { return 0.0 };
    match (process::parse_stack(&json, true), ()) {
        (Ok(stack), ()) => catch_unwind(AssertUnwindSafe(|| process::audio_preroll(&stack))).unwrap_or(0.0),
        _ => 0.0,
    }
}

/// Apply an audio-effect stack to interleaved stereo float samples in place.
/// Returns the number of effects applied, or a negative error code (see `nativeProcessImage`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftNative_nativeProcessAudio<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    samples: JFloatArray<'l>,
    rate: jint,
    start_seconds: jdouble,
    stack_json: JString<'l>,
) -> jint {
    if rate <= 0 {
        return -1;
    }
    let Some(json) = read_string(&mut env, &stack_json) else { return -1 };
    let stack = match process::parse_stack(&json, true) {
        Ok(stack) => stack,
        Err(_) => return -2,
    };
    if stack.is_empty() {
        return 0;
    }
    let Ok(len) = env.get_array_length(&samples) else { return -1 };
    let mut block = vec![0f32; len as usize];
    if env.get_float_array_region(&samples, 0, &mut block).is_err() {
        return -1;
    }
    let applied = match catch_unwind(AssertUnwindSafe(|| process::process_audio(&mut block, rate as u32, start_seconds, &stack))) {
        Ok(n) => n,
        Err(_) => return -3,
    };
    if env.set_float_array_region(&samples, 0, &block).is_err() {
        return -1;
    }
    applied as jint
}

#[cfg(test)]
mod tests;
