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
