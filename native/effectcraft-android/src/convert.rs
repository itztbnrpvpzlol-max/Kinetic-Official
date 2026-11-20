//! JSON <-> EffectCraft parameter values.

use std::collections::HashMap;

use effectcraft_effects::{EffectSpec, Params, default_value};
use effectcraft_keyframe::Value;
use effectcraft_project::ParamUi;
use serde_json::Value as Json;

/// Stable type tag for a parameter control, shared with the Kotlin side.
pub fn param_kind(ui: &ParamUi) -> &'static str {
    match ui {
        ParamUi::Number => "number",
        ParamUi::Slider { .. } => "slider",
        ParamUi::Percent => "percent",
        ParamUi::Angle => "angle",
        ParamUi::Pixels => "pixels",
        ParamUi::Point => "point",
        ParamUi::Point3 => "point3",
        ParamUi::Color => "color",
        ParamUi::Checkbox => "checkbox",
        ParamUi::Popup { .. } => "popup",
        ParamUi::Layer => "layer",
        ParamUi::Mask => "mask",
        ParamUi::Path => "path",
        ParamUi::Text => "text",
        ParamUi::File { .. } => "file",
        ParamUi::Gradient => "gradient",
        ParamUi::Hidden => "hidden",
    }
}

fn nums(j: &Json, n: usize) -> Option<Vec<f64>> {
    let a = j.as_array()?;
    if a.len() < n {
        return None;
    }
    a.iter().take(n).map(Json::as_f64).collect()
}

fn scalar(j: &Json) -> Option<f64> {
    match j {
        Json::Number(n) => n.as_f64(),
        Json::Bool(b) => Some(if *b { 1.0 } else { 0.0 }),
        _ => None,
    }
    .filter(|v| v.is_finite())
}

/// Convert one JSON value into the value kind the parameter declares. Point parameters arrive as
/// fractions of the layer (resolution independent) and leave as layer pixels, like EffectCraft's
/// own defaults. `None` keeps the parameter's default.
fn to_value(default: &Value, ui: &ParamUi, j: &Json, layer_size: [f64; 2]) -> Option<Value> {
    Some(match default {
        Value::Scalar(_) => Value::Scalar(scalar(j)?),
        Value::Bool(_) => Value::Bool(scalar(j)? >= 0.5),
        Value::Enum(_) => Value::Enum(scalar(j)?.round().max(0.0) as u32),
        Value::Vec2(_) => {
            let v = nums(j, 2)?;
            if matches!(ui, ParamUi::Point) {
                Value::Vec2([v[0] * layer_size[0], v[1] * layer_size[1]])
            } else {
                Value::Vec2([v[0], v[1]])
            }
        }
        Value::Vec3(_) => {
            let v = nums(j, 3)?;
            Value::Vec3([v[0], v[1], v[2]])
        }
        Value::Color(_) => {
            let v = nums(j, 3)?;
            let a = j.as_array().and_then(|a| a.get(3)).and_then(Json::as_f64).unwrap_or(1.0);
            Value::Color([v[0], v[1], v[2], a])
        }
        Value::Str(_) => Value::Str(j.as_str()?.to_owned()),
        _ => return None,
    })
}

/// Build the evaluated parameter set for one effect instance: every declared parameter at its
/// default, overridden by the values in `overrides` (`{ "paramId": value }`).
pub fn build_params(spec: &EffectSpec, overrides: Option<&serde_json::Map<String, Json>>, layer_size: [f64; 2]) -> Params {
    let mut values: HashMap<String, Value> =
        spec.params.iter().map(|p| (p.id.to_string(), default_value(p, layer_size))).collect();
    if let Some(map) = overrides {
        for p in &spec.params {
            if let Some(v) = map.get(p.id).and_then(|j| to_value(&p.default, &p.ui, j, layer_size)) {
                values.insert(p.id.to_string(), v);
            }
        }
    }
    Params { values }
}
