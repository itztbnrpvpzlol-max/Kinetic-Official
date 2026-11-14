# Kinetic Android MCP bridge

A local stdio MCP server for testing Kinetic (com.kinetic.app) on a physical Android phone connected to this computer with USB debugging. It uses ADB and deliberately does not expose a general-purpose shell tool.

## Requirements

- Node.js 18 or newer
- Android Platform Tools (adb) installed and available on PATH
- USB cable with data support
- Android Developer options and USB debugging enabled
- Phone unlocked and the computer's USB debugging authorization accepted

## First-time setup

1. Connect the phone to the computer by USB.
2. Enable USB debugging on the phone and accept the computer authorization prompt.
3. Verify the connection:

   ~~~sh
   adb devices
   ~~~

   The phone should show status "device", not "unauthorized" or "offline".

4. Install dependencies:

   ~~~sh
   cd tools/android-mcp
   npm install
   ~~~

5. Verify the server starts:

   ~~~sh
   npm start
   ~~~

   It waits silently because it communicates over stdio. Stop it with Ctrl+C.

## Connect from ChatGPT desktop or Codex

Add this server in the host's MCP server settings. Replace the example paths with the absolute path to this repository on your computer.

Example Codex config.toml entry:

~~~toml
[mcp_servers.kinetic_android]
command = "node"
args = ["/ABSOLUTE/PATH/TO/Kinetic-Official/tools/android-mcp/server.mjs"]
cwd = "/ABSOLUTE/PATH/TO/Kinetic-Official/tools/android-mcp"
~~~

For ChatGPT desktop, open Settings → MCP servers → Add server, choose STDIO, and enter the command and arguments above. Restart the host after saving. Availability depends on the desktop app/account and local-computer execution support.

ChatGPT web does not directly read a computer's local stdio process. It requires a supported Secure MCP Tunnel or a separately hosted HTTPS MCP endpoint. Do not expose ADB or this server directly to the public internet.

## Selecting a device

If exactly one authorized Android device is connected, it is selected automatically. If multiple devices are connected, set ANDROID_SERIAL in the MCP server environment to the intended serial. Get serials with adb devices. Tools also accept an optional serial argument.

## Available tools

| Tool | Purpose |
|---|---|
| list_devices | List authorized ADB devices |
| device_info | Read phone model, Android version, screen metrics, and RAM |
| take_screenshot | Return a screenshot image |
| launch_app | Launch Kinetic |
| restart_app | Relaunch Kinetic without clearing app data |
| tap | Tap screen coordinates |
| swipe | Swipe between coordinates |
| input_text | Enter basic text into a focused field |
| press_key | Press BACK, ENTER, or DPAD navigation |
| get_ui_tree | Read the visible UI hierarchy |
| get_logcat | Read recent Kinetic process logs |
| get_app_state | Read process, foreground activity, and memory summary |
| install_apk | Install/update an APK from the allowed repository output folders |

Coordinates are physical pixels. Take a fresh screenshot before tapping. APK installation uses adb install -r and preserves existing app data.

## Safety and privacy

- The server only targets com.kinetic.app.
- No arbitrary shell, file browsing, SMS, contacts, camera, microphone, or personal-data tools are provided.
- UI hierarchy and app logs may contain text visible inside Kinetic. Share only information you are comfortable exposing to the connected MCP client.
- Keep USB debugging enabled only while needed, and revoke computer authorizations from Android Developer options if you no longer trust a computer.
- The server runs locally and listens on no network port.
