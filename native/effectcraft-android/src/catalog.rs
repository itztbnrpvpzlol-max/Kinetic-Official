//! Effect catalog: every EffectCraft effect with its category and typed parameters, as JSON the
//! Kotlin side parses once to build the effect browser and the effect controls.

use effectcraft_effects::{ParamSpec, audio_fx};
use effectcraft_keyframe::Value;
use effectcraft_effects::all;
use serde_json::{Value as Json, json};

use crate::convert::param_kind;

fn default_json(spec: &ParamSpec) -> Json {
    match &spec.default {
        Value::Scalar(v) => json!(v),
        Value::Vec2(v) => json!(v),
        Value::Vec3(v) => json!(v),
        Value::Color(v) => json!(v),
        Value::Bool(b) => json!(b),
        Value::Enum(i) => json!(i),
        Value::Str(s) => json!(s),
        _ => Json::Null,
    }
}

fn param_json(spec: &ParamSpec) -> Json {
    use effectcraft_project::ParamUi as U;
    let mut o = serde_json::Map::new();
    o.insert("id".into(), json!(spec.id));
    o.insert("name".into(), json!(spec.name));
    o.insert("type".into(), json!(param_kind(&spec.ui)));
    o.insert("default".into(), default_json(spec));
    match &spec.ui {
        U::Slider { min, max, slider_min, slider_max, decimals } => {
            o.insert("min".into(), json!(min));
            o.insert("max".into(), json!(max));
            o.insert("sliderMin".into(), json!(slider_min));
            o.insert("sliderMax".into(), json!(slider_max));
            o.insert("decimals".into(), json!(decimals));
        }
        U::Popup { options } => {
            o.insert("options".into(), json!(options));
        }
        U::File { filters } => {
            o.insert("filters".into(), json!(filters));
        }
        _ => {}
    }
    Json::Object(o)
}

/// The whole catalog as a JSON document: `{ "version": .., "categories": [..], "effects": [..] }`.
pub fn catalog_json() -> String {
    let effects: Vec<Json> = all()
        .into_iter()
        .map(|s| {
            let params: Vec<Json> = s.params.iter().map(param_json).collect();
            json!({
                "id": s.id,
                "name": s.name,
                "category": s.category,
                "gpu": s.gpu,
                "float": s.float,
                "audio": audio_fx::is_audio_effect(s.id),
                "params": params,
            })
        })
        .collect();
    json!({
        "version": 1,
        "categories": effectcraft_effects::categories(),
        "effects": effects,
    })
    .to_string()
}
