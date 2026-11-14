#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import path from "node:path";
import fs from "node:fs/promises";
import { fileURLToPath } from "node:url";

const execFileAsync = promisify(execFile);
const ADB = process.env.ADB_PATH || "adb";
const PACKAGE = "com.kinetic.app";
const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(process.env.KINETIC_REPO || path.join(HERE, "../.."));

async function adb(args, options = {}) {
  try {
    return await execFileAsync(ADB, args, {
      encoding: options.encoding ?? "utf8",
      maxBuffer: options.maxBuffer ?? 8 * 1024 * 1024,
      timeout: options.timeout ?? 15000,
      windowsHide: true
    });
  } catch (error) {
    const detail = (error.stderr || error.message || "ADB command failed").toString().trim();
    throw new Error(detail.slice(0, 2500));
  }
}
async function devices() {
  const { stdout } = await adb(["devices", "-l"]);
  return stdout.split(/\r?\n/).slice(1).filter(line => /\bdevice\b/.test(line) && !/offline|unauthorized/.test(line))
    .map(line => {
      const [serial, ...parts] = line.trim().split(/\s+/);
      return { serial, details: parts.join(" ") };
    });
}
async function resolveSerial(requested) {
  const serial = requested || process.env.ANDROID_SERIAL;
  const found = await devices();
  if (serial) {
    if (!found.some(d => d.serial === serial)) throw new Error("Selected device is not connected or has not authorized USB debugging. Check adb devices and approve the phone prompt.");
    return serial;
  }
  if (found.length === 0) throw new Error("No authorized Android phone found. Connect by USB, enable USB debugging, approve the computer, then run adb devices.");
  if (found.length > 1) throw new Error("More than one Android device is connected. Set ANDROID_SERIAL or pass the serial to the tool.");
  return found[0].serial;
}
async function shell(serial, args, options = {}) {
  return adb(["-s", serial, "shell", ...args], options);
}
function textResult(value) {
  return { content: [{ type: "text", text: String(value) }] };
}

const server = new McpServer({
  name: "kinetic-android-test",
  version: "1.0.0"
}, {
  instructions: "Use this MCP server only to test Kinetic (com.kinetic.app) on the user's explicitly connected physical Android phone. Never attempt arbitrary shell commands. Prefer screenshots and UI hierarchy before interacting. Coordinates are physical screen pixels. Do not install or overwrite an APK unless the user has asked for that test/build to be installed."
});

server.tool("list_devices", "List Android phones currently authorized over ADB.", {}, async () => {
  const found = await devices();
  return textResult(found.length ? found.map(d => d.serial + "  " + d.details).join("\n") : "No authorized Android devices found.");
});

server.tool("device_info", "Read model, Android version, screen size/density, and available memory.", {
  serial: z.string().optional()
}, async ({ serial: requested }) => {
  const serial = await resolveSerial(requested);
  const props = await shell(serial, ["getprop"]);
  const get = key => {
    const match = props.stdout.match(new RegExp("\\[" + key + "\\]: \\[(.*?)\\]"));
    return match?.[1] || "unknown";
  };
  const [size, density, memory] = await Promise.all([
    shell(serial, ["wm", "size"]), shell(serial, ["wm", "density"]), shell(serial, ["cat", "/proc/meminfo"])
  ]);
  const total = memory.stdout.match(/MemTotal:\s+(\d+)/)?.[1];
  const available = memory.stdout.match(/MemAvailable:\s+(\d+)/)?.[1];
  return textResult([
    "Serial: " + serial,
    "Manufacturer: " + get("ro.product.manufacturer"),
    "Model: " + get("ro.product.model"),
    "Android: " + get("ro.build.version.release") + " (API " + get("ro.build.version.sdk") + ")",
    "Screen: " + size.stdout.trim(),
    "Density: " + density.stdout.trim(),
    "RAM total: " + (total ? Math.round(Number(total) / 1024) + " MB" : "unknown"),
    "RAM available: " + (available ? Math.round(Number(available) / 1024) + " MB" : "unknown")
  ].join("\n"));
});

server.tool("take_screenshot", "Capture the current phone screen and return a PNG image.", {
  serial: z.string().optional()
}, async ({ serial: requested }) => {
  const serial = await resolveSerial(requested);
  const { stdout } = await adb(["-s", serial, "exec-out", "screencap", "-p"], { encoding: "buffer", maxBuffer: 16 * 1024 * 1024 });
  return { content: [{ type: "image", data: stdout.toString("base64"), mimeType: "image/png" }] };
});

server.tool("launch_app", "Launch Kinetic on the connected phone.", {
  serial: z.string().optional()
}, async ({ serial: requested }) => {
  const serial = await resolveSerial(requested);
  await shell(serial, ["monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1"]);
  return textResult("Launch request sent to Kinetic.");
});

server.tool("restart_app", "Force-stop and relaunch Kinetic without clearing projects or app data.", {
  serial: z.string().optional()
}, async ({ serial: requested }) => {
  const serial = await resolveSerial(requested);
  await shell(serial, ["am", "force-stop", PACKAGE]);
  await shell(serial, ["monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1"]);
  return textResult("Kinetic was relaunched. App data was not cleared.");
});

server.tool("tap", "Tap a point on the phone screen using physical pixel coordinates.", {
  x: z.number().int().min(0).max(10000), y: z.number().int().min(0).max(10000), serial: z.string().optional()
}, async ({ x, y, serial: requested }) => {
  const serial = await resolveSerial(requested);
  const { stdout } = await shell(serial, ["wm", "size"]);
  const match = stdout.match(/(\d+)x(\d+)/);
  if (match && (x >= Number(match[1]) || y >= Number(match[2]))) throw new Error("Coordinates exceed screen bounds " + match[1] + "x" + match[2] + ".");
  await shell(serial, ["input", "tap", String(x), String(y)]);
  return textResult("Tapped (" + x + ", " + y + ").");
});

server.tool("swipe", "Swipe between two physical screen points. Duration is limited to 100–1500 ms.", {
  x1: z.number().int().min(0).max(10000), y1: z.number().int().min(0).max(10000),
  x2: z.number().int().min(0).max(10000), y2: z.number().int().min(0).max(10000),
  duration_ms: z.number().int().min(100).max(1500).default(350), serial: z.string().optional()
}, async ({ x1, y1, x2, y2, duration_ms, serial: requested }) => {
  const serial = await resolveSerial(requested);
  await shell(serial, ["input", "swipe", String(x1), String(y1), String(x2), String(y2), String(duration_ms)]);
  return textResult("Swipe completed.");
});

server.tool("input_text", "Type basic text into the currently focused field.", {
  text: z.string().min(1).max(200), serial: z.string().optional()
}, async ({ text: value, serial: requested }) => {
  if (!/^[A-Za-z0-9 .,!?@_:+()\-/#%]*$/.test(value)) throw new Error("Only basic Latin letters, numbers, spaces, and common punctuation are accepted.");
  const serial = await resolveSerial(requested);
  await shell(serial, ["input", "text", value.replace(/ /g, "%s")]);
  return textResult("Text input sent.");
});

server.tool("press_key", "Press BACK, ENTER, or a DPAD navigation key.", {
  key: z.enum(["BACK", "ENTER", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT"]), serial: z.string().optional()
}, async ({ key, serial: requested }) => {
  const serial = await resolveSerial(requested);
  const codes = { BACK: "4", ENTER: "66", DPAD_UP: "19", DPAD_DOWN: "20", DPAD_LEFT: "21", DPAD_RIGHT: "22" };
  await shell(serial, ["input", "keyevent", codes[key]]);
  return textResult("Pressed " + key + ".");
});

server.tool("get_ui_tree", "Dump the visible Android accessibility/UI hierarchy as XML.", {
  serial: z.string().optional()
}, async ({ serial: requested }) => {
  const serial = await resolveSerial(requested);
  const remote = "/sdcard/kinetic_mcp_ui.xml";
  await shell(serial, ["uiautomator", "dump", remote]);
  const { stdout } = await shell(serial, ["cat", remote], { maxBuffer: 2 * 1024 * 1024 });
  await shell(serial, ["rm", "-f", remote]);
  return textResult(stdout.slice(0, 100000));
});

server.tool("get_logcat", "Read recent logcat entries for Kinetic only. Limit is 20–1000 lines.", {
  lines: z.number().int().min(20).max(1000).default(250), serial: z.string().optional()
}, async ({ lines, serial: requested }) => {
  const serial = await resolveSerial(requested);
  const pidResult = await shell(serial, ["pidof", PACKAGE]);
  const pid = pidResult.stdout.trim().split(/\s+/)[0];
  if (!pid) {
    const { stdout } = await shell(serial, ["logcat", "-d", "-t", String(lines), "-s", "AndroidRuntime:E", "ActivityTaskManager:W"]);
    return textResult("Kinetic is not currently running.\n" + stdout.slice(-50000));
  }
  const { stdout } = await shell(serial, ["logcat", "-d", "-t", String(lines), "--pid", pid]);
  return textResult(stdout.slice(-70000) || "No recent Kinetic log entries.");
});

server.tool("get_app_state", "Check Kinetic process status, foreground activity, and process memory summary.", {
  serial: z.string().optional()
}, async ({ serial: requested }) => {
  const serial = await resolveSerial(requested);
  const pidResult = await shell(serial, ["pidof", PACKAGE]);
  const pid = pidResult.stdout.trim().split(/\s+/)[0];
  const activity = await shell(serial, ["dumpsys", "activity", "activities"]);
  const resumed = activity.stdout.split(/\r?\n/).filter(line => /mResumedActivity|topResumedActivity/.test(line)).slice(-3).join("\n");
  if (!pid) return textResult("Kinetic process: not running\nForeground activity:\n" + (resumed || "not available"));
  const mem = await shell(serial, ["dumpsys", "meminfo", PACKAGE]);
  const summary = mem.stdout.split(/\r?\n/).filter(line => /TOTAL PSS|TOTAL RSS|Native Heap|Dalvik Heap|Graphics/.test(line)).slice(0, 12).join("\n");
  return textResult("Kinetic PID: " + pid + "\nForeground activity:\n" + (resumed || "not available") + "\n\nMemory summary:\n" + (summary || "not available"));
});

server.tool("install_apk", "Install/update Kinetic from a local APK inside app/build/outputs/apk or apks. Existing app data is preserved.", {
  apk_path: z.string().min(1), serial: z.string().optional()
}, async ({ apk_path, serial: requested }) => {
  const serial = await resolveSerial(requested);
  const real = await fs.realpath(path.resolve(REPO_ROOT, apk_path));
  const allowedRoots = [path.join(REPO_ROOT, "app", "build", "outputs", "apk"), path.join(REPO_ROOT, "apks")];
  const allowed = allowedRoots.some(root => {
    const rel = path.relative(root, real);
    return !rel.startsWith("..") && !path.isAbsolute(rel);
  });
  if (!allowed || !real.toLowerCase().endsWith(".apk")) throw new Error("APK must be inside app/build/outputs/apk or apks.");
  await adb(["-s", serial, "install", "-r", real], { timeout: 120000, maxBuffer: 2 * 1024 * 1024 });
  return textResult("Installed " + path.basename(real) + " on " + serial + ". Existing app data was preserved.");
});

const transport = new StdioServerTransport();
await server.connect(transport);
