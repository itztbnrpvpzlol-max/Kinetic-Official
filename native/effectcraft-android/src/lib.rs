use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Mutex, OnceLock};

use effectcraft_engine::Session;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;
use serde_json::{Value, json};

static SESSION: OnceLock<Mutex<Session>> = OnceLock::new();

fn session() -> &'static Mutex<Session> {
    SESSION.get_or_init(|| Mutex::new(Session::default()))
}

fn response_string(env: &mut JNIEnv<'_>, value: Value) -> jstring {
    let encoded = serde_json::to_string(&value).unwrap_or_else(|_| {
        String::from(r#"{\"protocolVersion\":1,\"ok\":false,\"error\":\"Could not encode engine response\"}"#)
    });
    match env.new_string(encoded) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn error_response(message: impl Into<String>) -> Value {
    json!({
        "protocolVersion": 1,
        "ok": false,
        "error": message.into()
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftNative_nativeInitialize(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    match catch_unwind(|| {
        let _ = session();
    }) {
        Ok(()) => 1,
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftNative_nativeExecute(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    command_id: JString<'_>,
    params_json: JString<'_>,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        let command_id = match env.get_string(&command_id) {
            Ok(value) => value.to_string_lossy().into_owned(),
            Err(_) => return error_response("Could not read command ID from Kotlin"),
        };
        let params_text = match env.get_string(&params_json) {
            Ok(value) => value.to_string_lossy().into_owned(),
            Err(_) => return error_response("Could not read command parameters from Kotlin"),
        };
        let params: Value = match serde_json::from_str::<Value>(&params_text) {
            Ok(value) if value.is_object() => value,
            Ok(_) => return error_response("Command parameters must be a JSON object"),
            Err(error) => return error_response(format!("Invalid command JSON: {error}")),
        };

        let mut engine = match session().lock() {
            Ok(engine) => engine,
            Err(_) => return error_response("EffectCraft session lock is unavailable"),
        };

        let result = engine.execute_checked(&command_id, params);
        let events = engine.drain_events();
        match result {
            Ok(value) => json!({
                "protocolVersion": 1,
                "ok": true,
                "result": value,
                "revision": engine.revision,
                "events": events
            }),
            Err(error) => json!({
                "protocolVersion": 1,
                "ok": false,
                "error": error.to_string(),
                "revision": engine.revision,
                "events": events
            }),
        }
    }));

    match outcome {
        Ok(value) => response_string(&mut env, value),
        Err(_) => response_string(&mut env, error_response("EffectCraft encountered an internal panic")),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftNative_nativeExecuteBatch(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    commands_json: JString<'_>,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        let commands_text = match env.get_string(&commands_json) {
            Ok(value) => value.to_string_lossy().into_owned(),
            Err(_) => return error_response("Could not read command batch from Kotlin"),
        };
        let commands: Vec<Value> = match serde_json::from_str(&commands_text) {
            Ok(value) => value,
            Err(error) => return error_response(format!("Invalid command batch JSON: {error}")),
        };
        let mut engine = match session().lock() {
            Ok(engine) => engine,
            Err(_) => return error_response("EffectCraft session lock is unavailable"),
        };
        let mut results = Vec::with_capacity(commands.len());
        let mut events = Vec::new();
        let mut ok = true;
        for (index, entry) in commands.iter().enumerate() {
            let Some(command_id) = entry.get("command").and_then(Value::as_str) else {
                results.push(json!({"ok": false, "error": format!("Batch entry {index} is missing command")}));
                ok = false;
                break;
            };
            let params = entry.get("params").cloned().unwrap_or_else(|| json!({}));
            if !params.is_object() {
                results.push(json!({"command": command_id, "ok": false, "error": "Command params must be an object"}));
                ok = false;
                break;
            }
            match engine.execute_checked(command_id, params) {
                Ok(value) => results.push(json!({"command": command_id, "ok": true, "result": value})),
                Err(error) => {
                    results.push(json!({"command": command_id, "ok": false, "error": error.to_string()}));
                    ok = false;
                }
            }
            events.extend(engine.drain_events());
            if !ok {
                break;
            }
        }
        json!({
            "protocolVersion": 1,
            "ok": ok,
            "results": results,
            "revision": engine.revision,
            "events": events
        })
    }));
    match outcome {
        Ok(value) => response_string(&mut env, value),
        Err(_) => response_string(&mut env, error_response("EffectCraft encountered an internal panic during a command batch")),
    }
}


use std::collections::HashMap;
use std::sync::{Arc, RwLock};

use effectcraft_media::MediaPool;

static MEDIA_POOL: OnceLock<MediaPool> = OnceLock::new();
static MEDIA_FOOTAGE: OnceLock<Mutex<HashMap<String, effectcraft_engine::project::Footage>>> = OnceLock::new();

fn media_pool() -> &'static MediaPool {
    MEDIA_POOL.get_or_init(|| {
        // 64 MiB keeps the decoded-frame LRU useful without exhausting a 32-bit
        // Android process. MediaPool owns decoder reuse and sequential read-ahead.
        let pool = MediaPool::with_budget(64 * 1024 * 1024);
        pool.set_read_ahead(true);
        pool
    })
}

fn media_footage(path: &str) -> Result<effectcraft_engine::project::Footage, String> {
    let cache = MEDIA_FOOTAGE.get_or_init(|| Mutex::new(HashMap::new()));
    if let Some(footage) = cache.lock().map_err(|_| "EffectCraft footage cache lock unavailable")?.get(path).cloned() {
        return Ok(footage);
    }
    let footage = effectcraft_media::probe(path).map_err(|error| format!("Could not probe media: {error}"))?;
    if footage.kind != FootageKind::Video || !footage.has_video {
        return Err("EffectCraft MediaPool only accepts video footage".into());
    }
    cache.lock().map_err(|_| "EffectCraft footage cache lock unavailable")?
        .insert(path.to_string(), footage.clone());
    Ok(footage)
}

use effectcraft_engine::project::{Footage, FootageKind, ItemId, ItemKind, LayerId, LayerSource};
use effectcraft_engine::raster::Image;
use effectcraft_engine::render::FootageSource;
use effectcraft_engine::time::Tick;

struct MemoryFootage {
    item: ItemId,
    frame: RwLock<Arc<Image>>,
}

impl MemoryFootage {
    fn replace(&self, rgba: &[u8], width: u32, height: u32) -> Result<(), String> {
        let expected = width as usize * height as usize * 4;
        if width == 0 || height == 0 || rgba.len() != expected {
            return Err("Invalid input frame dimensions".into());
        }
        let mut image = Image::new(width, height);
        for (dst, src) in image.data.iter_mut().zip(rgba.chunks_exact(4)) {
            let a = f32::from(src[3]) / 255.0;
            *dst = [
                (f32::from(src[0]) / 255.0) * a,
                (f32::from(src[1]) / 255.0) * a,
                (f32::from(src[2]) / 255.0) * a,
                a,
            ];
        }
        *self.frame.write().map_err(|_| "Frame buffer lock unavailable")? = Arc::new(image);
        Ok(())
    }
}

impl FootageSource for MemoryFootage {
    fn frame(&self, item: ItemId, _footage: &Footage, _time: Tick) -> Option<Arc<Image>> {
        if item != self.item { return None; }
        self.frame.read().ok().map(|image| image.clone())
    }
}

struct RenderContext {
    session: Session,
    source: Arc<MemoryFootage>,
    comp: ItemId,
    layer: LayerId,
    width: u32,
    height: u32,
    effect_ids: String,
    parameter_state: String,
}

static RENDER_CONTEXTS: OnceLock<Mutex<HashMap<String, RenderContext>>> = OnceLock::new();

fn render_contexts() -> &'static Mutex<HashMap<String, RenderContext>> {
    RENDER_CONTEXTS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn create_render_context(width: u32, height: u32, effects: &Value, rgba: &[u8]) -> Result<RenderContext, String> {
    if width == 0 || height == 0 || width > 8192 || height > 8192 {
        return Err("Unsupported frame dimensions".into());
    }
    let mut engine = Session::default();
    let comp_value = engine.execute("comp.new", json!({
        "name": "Kinetic Preview",
        "width": width,
        "height": height,
        "frameRate": 30,
        "duration": 3600.0,
        "background": [0.0, 0.0, 0.0]
    })).map_err(|e| e.to_string())?;
    let comp = ItemId(comp_value.get("comp").and_then(Value::as_u64).ok_or("EffectCraft did not create a composition")?);
    let layer_value = engine.execute("layer.newSolid", json!({
        "comp": comp.0,
        "name": "Kinetic Source Frame",
        "width": width,
        "height": height,
        "color": "#ffffff"
    })).map_err(|e| e.to_string())?;
    let layer = LayerId(layer_value.get("layer").and_then(Value::as_u64).ok_or("EffectCraft did not create a source layer")?);

    let mut ids = Vec::new();
    if let Some(list) = effects.as_array() {
        for effect in list {
            let id = effect.get("id").and_then(Value::as_str).or_else(|| effect.get("name").and_then(Value::as_str))
                .ok_or("Effect entry is missing its EffectCraft ID")?;
            engine.execute("effect.apply", json!({"comp": comp.0, "layers": [layer.0], "effect": id}))
                .map_err(|e| format!("Could not apply EffectCraft effect {id}: {e}"))?;
            ids.push(id);
        }
    }

    let solid_item = {
        let c = engine.project.comp(comp).ok_or("EffectCraft composition disappeared")?;
        let l = c.layer(layer).ok_or("EffectCraft source layer disappeared")?;
        match &l.source {
            LayerSource::Solid { item } => *item,
            _ => return Err("EffectCraft source layer is not a solid".into()),
        }
    };
    let source = Arc::new(MemoryFootage {
        item: solid_item,
        frame: RwLock::new(Arc::new(Image::new(width, height))),
    });
    {
        let project = Arc::make_mut(&mut engine.project);
        let item = project.item_mut(solid_item).ok_or("EffectCraft solid item disappeared")?;
        item.kind = ItemKind::Footage(Footage {
            path: "kinetic://memory-frame".into(),
            kind: FootageKind::Still,
            width,
            height,
            pixel_aspect: 1.0,
            frame_rate: effectcraft_engine::time::FrameRate::FPS_30,
            duration: Tick::from_seconds_f64(3600.0),
            has_video: true,
            ..Footage::default()
        });
        let l = project.comp_mut(comp).and_then(|c| c.layer_mut(layer)).ok_or("EffectCraft source layer disappeared")?;
        l.source = LayerSource::Footage { item: solid_item };
    }
    engine.footage = source.clone();
    engine.bump();
    source.replace(rgba, width, height)?;
    Ok(RenderContext {
        session: engine,
        source,
        comp,
        layer,
        width,
        height,
        effect_ids: serde_json::to_string(&ids).map_err(|e| e.to_string())?,
        parameter_state: String::new(),
    })
}

fn update_effect_parameters(context: &mut RenderContext, effects: &Value) -> Result<(), String> {
    let state = serde_json::to_string(effects).map_err(|e| e.to_string())?;
    if state == context.parameter_state {
        return Ok(());
    }
    let mut effect_index = 0usize;
    if let Some(list) = effects.as_array() {
        for effect in list {
            effect_index += 1;
            let Some(values) = effect.get("values").and_then(Value::as_object) else { continue; };
            for (name, value) in values {
                let path = format!("effects/#{effect_index}/{name}");
                context.session.execute("prop.set", json!({
                    "comp": context.comp.0,
                    "layer": context.layer.0,
                    "path": path,
                    "value": value,
                    "merge": "kinetic-effectcraft-preview"
                })).map_err(|e| format!("Could not set EffectCraft parameter {path}: {e}"))?;
            }
        }
    }
    context.parameter_state = state;
    Ok(())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftNative_nativeRenderFrame(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    cache_key: JString<'_>,
    frame: jni::objects::JByteArray<'_>,
    width: jni::sys::jint,
    height: jni::sys::jint,
    effects_json: JString<'_>,
    time_seconds: jni::sys::jfloat,
    max_side: jni::sys::jint,
) -> jni::sys::jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<u8>, String> {
        let key = env.get_string(&cache_key).map_err(|e| e.to_string())?.to_string_lossy().into_owned();
        let effects_text = env.get_string(&effects_json).map_err(|e| e.to_string())?.to_string_lossy().into_owned();
        let effects: Value = serde_json::from_str(&effects_text).map_err(|e| format!("Invalid EffectCraft effect list: {e}"))?;
        let w = u32::try_from(width).map_err(|_| "Invalid frame width")?;
        let h = u32::try_from(height).map_err(|_| "Invalid frame height")?;
        let input_len = env.get_array_length(&frame).map_err(|e| e.to_string())? as usize;
        let mut input = vec![0i8; input_len];
        env.get_byte_array_region(&frame, 0, &mut input).map_err(|e| e.to_string())?;
        let rgba: Vec<u8> = input.into_iter().map(|v| v as u8).collect();
        if rgba.len() != w as usize * h as usize * 4 {
            return Err("Input RGBA buffer length does not match its dimensions".into());
        }
        let effect_ids = effects.as_array().map(|list| {
        list.iter().map(|effect| effect.get("id").or_else(|| effect.get("name")).cloned().unwrap_or(Value::Null)).collect::<Vec<_>>()
    }).unwrap_or_default();
    let ids = serde_json::to_string(&effect_ids).map_err(|e| e.to_string())?;
        let mut contexts = render_contexts().lock().map_err(|_| "EffectCraft render cache lock unavailable")?;
        let rebuild = contexts.get(&key).is_none_or(|c| c.width != w || c.height != h || c.effect_ids != ids);
        if rebuild {
            if contexts.len() >= 3 {
                if let Some(stale) = contexts.keys().find(|existing| *existing != &key).cloned() {
                    contexts.remove(&stale);
                }
            }
            let context = create_render_context(w, h, &effects, &rgba)?;
            contexts.insert(key.clone(), context);
        }
        let context = contexts.get_mut(&key).ok_or("EffectCraft render context unavailable")?;
        context.source.replace(&rgba, w, h)?;
        update_effect_parameters(context, &effects)?;
        // The memory footage has a stable virtual path but new pixels on every preview tick.
        // Never let EffectCraft reuse a layer buffer rendered from the previous source frame.
        context.session.layer_cache.clear();
        let (out_w, out_h, pixels) = context.session.render_rgba8_alpha(
            context.comp,
            Tick::from_seconds_f64(f64::from(time_seconds)),
            u32::try_from(max_side).unwrap_or(0),
            true,
        ).map_err(|e| e.to_string())?;
        let mut out = Vec::with_capacity(8 + pixels.len());
        out.extend_from_slice(&out_w.to_le_bytes());
        out.extend_from_slice(&out_h.to_le_bytes());
        out.extend_from_slice(&pixels);
        Ok(out)
    }));
    match result {
        Ok(Ok(bytes)) => env.byte_array_from_slice(&bytes).map(|array| array.into_raw()).unwrap_or(std::ptr::null_mut()),
        Ok(Err(error)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            std::ptr::null_mut()
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "EffectCraft renderer panicked");
            std::ptr::null_mut()
        }
    }
}


/// Decode one requested preview frame through EffectCraft's persistent MediaPool.
/// The returned byte array is [width:u32 LE][height:u32 LE][RGBA8 pixels].
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftNative_nativeDecodeVideoFrame(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
    time_seconds: jni::sys::jfloat,
    max_side: jni::sys::jint,
) -> jni::sys::jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<u8>, String> {
        let path = env.get_string(&path).map_err(|e| e.to_string())?
            .to_string_lossy().into_owned();
        if path.is_empty() || !std::path::Path::new(&path).is_file() {
            return Err("Video path is not a readable local file".into());
        }
        if !time_seconds.is_finite() || max_side <= 0 {
            return Err("Invalid video frame request".into());
        }
        let footage = media_footage(&path)?;
        let source_width = footage.width.max(1);
        let source_height = footage.height.max(1);
        let edge = (max_side as u32).clamp(128, 2048);
        let scale = (edge as f64 / f64::from(source_width.max(source_height))).min(1.0);
        let width = ((f64::from(source_width) * scale).round() as u32).max(1);
        let height = ((f64::from(source_height) * scale).round() as u32).max(1);
        if u64::from(width) * u64::from(height) > 8_388_608 {
            return Err("Requested preview frame exceeds the pixel budget".into());
        }
        let tick = Tick::from_seconds_f64(f64::from(time_seconds.max(0.0)));
        let frame = FootageSource::frame_at_size(
            media_pool(), ItemId(0), &footage, tick, width, height
        ).ok_or_else(|| "EffectCraft could not decode the requested video frame".to_string())?;
        let pixels = frame.to_rgba8();
        if pixels.len() != width as usize * height as usize * 4 {
            return Err("EffectCraft returned an invalid RGBA frame".into());
        }
        let mut output = Vec::with_capacity(8 + pixels.len());
        output.extend_from_slice(&width.to_le_bytes());
        output.extend_from_slice(&height.to_le_bytes());
        output.extend_from_slice(&pixels);
        Ok(output)
    }));
    match result {
        Ok(Ok(bytes)) => env.byte_array_from_slice(&bytes)
            .map(|array| array.into_raw()).unwrap_or(std::ptr::null_mut()),
        _ => std::ptr::null_mut(),
    }
}


/// Release decoded-frame memory when Android reports memory pressure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftNative_nativeClearMediaFrames(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    let _ = catch_unwind(|| media_pool().clear_frames());
}


// -----------------------------------------------------------------------------
// Dedicated AI authoring session.
// This is intentionally separate from the editor's interactive EffectCraft
// session above. The AI can use the same complete engine command registry,
// effect registry, presets and renderer without touching Kinetic's editor state.
// -----------------------------------------------------------------------------

static AI_SESSION: OnceLock<Mutex<Session>> = OnceLock::new();

struct NativeAiImporter;

impl effectcraft_engine::Importer for NativeAiImporter {
    fn probe(&self, path: &str) -> Result<Footage, String> {
        effectcraft_media::probe(path).map_err(|error| error.to_string())
    }
}

fn new_ai_session() -> Session {
    let mut engine = Session::default();
    engine.footage = Arc::new(media_pool().clone());
    engine.importer = Some(Arc::new(NativeAiImporter));
    engine
}

fn ai_session() -> &'static Mutex<Session> {
    AI_SESSION.get_or_init(|| Mutex::new(new_ai_session()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftAiNative_nativeInitialize(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    match catch_unwind(|| {
        let _ = ai_session();
    }) {
        Ok(()) => 1,
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftAiNative_nativeReset(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jboolean {
    match catch_unwind(AssertUnwindSafe(|| {
        let mut engine = ai_session().lock().map_err(|_| "AI EffectCraft session lock is unavailable")?;
        *engine = new_ai_session();
        Ok::<(), String>(())
    })) {
        Ok(Ok(())) => 1,
        _ => 0,
    }
}

/// Discover the complete engine command registry, effects, and built-in animation
/// presets from the pinned EffectCraft build. No Kinetic layer model is involved.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftAiNative_nativeDiscover(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        let mut engine = match ai_session().lock() {
            Ok(engine) => engine,
            Err(_) => return error_response("AI EffectCraft session lock is unavailable"),
        };

        let commands = effectcraft_engine::command_specs().iter().map(|spec| {
            let enabled = (spec.enabled)(&engine);
            json!({
                "id": spec.id,
                "label": spec.label,
                "menu": spec.menu,
                "shortcut": spec.shortcut,
                "params": spec.params,
                "enabled": enabled.is_ok(),
                "disabledReason": enabled.err()
            })
        }).collect::<Vec<_>>();

        let effects = engine.execute_checked("effect.list", json!({}))
            .unwrap_or_else(|error| json!({"error": error.to_string()}));
        let animation_presets = effectcraft_engine::animation_presets()
            .into_iter().map(|(id, name, category)| json!({
                "id": id, "name": name, "category": category
            })).collect::<Vec<_>>();
        let text_presets = effectcraft_engine::text_presets()
            .into_iter().map(|(id, name)| json!({
                "id": id, "name": name
            })).collect::<Vec<_>>();

        json!({
            "protocolVersion": 1,
            "ok": true,
            "engine": "EffectCraft",
            "revision": engine.revision,
            "commandCount": commands.len(),
            "effectCount": effects.as_array().map_or(0, Vec::len),
            "commands": commands,
            "effects": effects,
            "animationPresets": animation_presets,
            "textPresets": text_presets
        })
    }));

    match outcome {
        Ok(value) => response_string(&mut env, value),
        Err(_) => response_string(&mut env, error_response("EffectCraft AI capability discovery panicked")),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftAiNative_nativeExecute(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    command_id: JString<'_>,
    params_json: JString<'_>,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        let command_id = match env.get_string(&command_id) {
            Ok(value) => value.to_string_lossy().into_owned(),
            Err(_) => return error_response("Could not read AI command ID from Kotlin"),
        };
        let params_text = match env.get_string(&params_json) {
            Ok(value) => value.to_string_lossy().into_owned(),
            Err(_) => return error_response("Could not read AI command parameters from Kotlin"),
        };
        let params: Value = match serde_json::from_str::<Value>(&params_text) {
            Ok(value) if value.is_object() => value,
            Ok(_) => return error_response("AI command parameters must be a JSON object"),
            Err(error) => return error_response(format!("Invalid AI command JSON: {error}")),
        };

        let mut engine = match ai_session().lock() {
            Ok(engine) => engine,
            Err(_) => return error_response("AI EffectCraft session lock is unavailable"),
        };
        let result = engine.execute_checked(&command_id, params);
        let events = engine.drain_events();
        match result {
            Ok(value) => json!({
                "protocolVersion": 1,
                "ok": true,
                "result": value,
                "revision": engine.revision,
                "events": events
            }),
            Err(error) => json!({
                "protocolVersion": 1,
                "ok": false,
                "error": error.to_string(),
                "revision": engine.revision,
                "events": events
            }),
        }
    }));

    match outcome {
        Ok(value) => response_string(&mut env, value),
        Err(_) => response_string(&mut env, error_response("EffectCraft AI command execution panicked")),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftAiNative_nativeExecuteBatch(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    commands_json: JString<'_>,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        let commands_text = match env.get_string(&commands_json) {
            Ok(value) => value.to_string_lossy().into_owned(),
            Err(_) => return error_response("Could not read AI command batch from Kotlin"),
        };
        let commands: Vec<Value> = match serde_json::from_str(&commands_text) {
            Ok(value) => value,
            Err(error) => return error_response(format!("Invalid AI command batch JSON: {error}")),
        };
        let mut engine = match ai_session().lock() {
            Ok(engine) => engine,
            Err(_) => return error_response("AI EffectCraft session lock is unavailable"),
        };
        let mut results = Vec::with_capacity(commands.len());
        let mut events = Vec::new();
        let mut ok = true;
        for (index, entry) in commands.iter().enumerate() {
            let Some(command_id) = entry.get("command").and_then(Value::as_str) else {
                results.push(json!({"ok": false, "error": format!("AI batch entry {index} is missing command")}));
                ok = false;
                break;
            };
            let params = entry.get("params").cloned().unwrap_or_else(|| json!({}));
            if !params.is_object() {
                results.push(json!({"command": command_id, "ok": false, "error": "AI batch params must be an object"}));
                ok = false;
                break;
            }
            match engine.execute_checked(command_id, params) {
                Ok(value) => results.push(json!({"command": command_id, "ok": true, "result": value})),
                Err(error) => {
                    results.push(json!({"command": command_id, "ok": false, "error": error.to_string()}));
                    ok = false;
                }
            }
            events.extend(engine.drain_events());
            if !ok {
                break;
            }
        }
        json!({
            "protocolVersion": 1,
            "ok": ok,
            "results": results,
            "revision": engine.revision,
            "events": events
        })
    }));

    match outcome {
        Ok(value) => response_string(&mut env, value),
        Err(_) => response_string(&mut env, error_response("EffectCraft AI command batch panicked")),
    }
}

/// Render a complete AI-authored EffectCraft composition through the native
/// compositor. The comp id is returned by comp.new / project inspection commands.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kinetic_app_engine_EffectCraftAiNative_nativeRenderFrame(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    comp_id: jni::sys::jlong,
    time_seconds: jni::sys::jfloat,
    max_side: jni::sys::jint,
) -> jni::sys::jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<Vec<u8>, String> {
        if comp_id < 0 || !time_seconds.is_finite() || max_side <= 0 {
            return Err("Invalid EffectCraft AI render request".into());
        }
        let mut engine = ai_session().lock().map_err(|_| "AI EffectCraft session lock is unavailable")?;
        let (width, height, pixels) = engine.render_rgba8_alpha(
            ItemId(comp_id as u64),
            Tick::from_seconds_f64(f64::from(time_seconds)),
            max_side as u32,
            true,
        ).map_err(|error| error.to_string())?;
        let mut output = Vec::with_capacity(8 + pixels.len());
        output.extend_from_slice(&width.to_le_bytes());
        output.extend_from_slice(&height.to_le_bytes());
        output.extend_from_slice(&pixels);
        Ok(output)
    }));

    match result {
        Ok(Ok(bytes)) => env.byte_array_from_slice(&bytes)
            .map(|array| array.into_raw()).unwrap_or(std::ptr::null_mut()),
        Ok(Err(error)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            std::ptr::null_mut()
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "EffectCraft AI renderer panicked");
            std::ptr::null_mut()
        }
    }
}
