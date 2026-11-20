//! Prints the EffectCraft catalog as JSON: `cargo run --example dump_catalog > catalog.json`.
fn main() {
    println!("{}", kinetic_effectcraft::catalog::catalog_json());
}
