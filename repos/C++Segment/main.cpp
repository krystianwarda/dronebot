#include <iostream>
#include <string>
#include <algorithm>
#include <thread>
#include <chrono>
#include <vector>
#include <iomanip>
#include <sstream>
#include <cstring>

#include <SDL2/SDL.h>

#ifdef _WIN32
#include <WinSock2.h>
#include <WS2tcpip.h>
#pragma comment(lib, "Ws2_32.lib")
#else
#include <sys/types.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <unistd.h>
#endif

static std::string toLower(const std::string &s) {
 std::string out = s;
 std::transform(out.begin(), out.end(), out.begin(), ::tolower);
 return out;
}

float normalizeAxis(Sint16 v) {
 // Normalize to [-1,1]
 if (v >=0) return (float)v /32767.0f;
 return (float)v /32768.0f; // negative side
}

// Target matching name (case-insensitive). Can be overridden by --name argument.
static std::string g_target_name = "radiomaster pocket joystick";

// Streaming configuration (local)
static const char* STREAM_IP = "127.0.0.1"; // localhost
static const int STREAM_PORT =9000; // port to send JSON to

// Socket handles
#ifdef _WIN32
static SOCKET g_sock = INVALID_SOCKET;
#else
 static int g_sock = -1;
#endif
static struct sockaddr_in g_dest_addr;

bool init_stream() {
#ifdef _WIN32
 WSADATA wsaData;
 int r = WSAStartup(MAKEWORD(2,2), &wsaData);
 if (r !=0) {
 std::cerr << "WSAStartup failed: " << r << "\n";
 return false;
 }
#endif

 // create TCP socket
 g_sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
 if (
#ifdef _WIN32
 g_sock == INVALID_SOCKET
#else
 g_sock <0
#endif
 ) {
#ifdef _WIN32
 std::cerr << "socket() failed: " << WSAGetLastError() << "\n";
#else
 std::cerr << "socket() failed\n";
#endif
 return false;
 }

 std::memset(&g_dest_addr,0, sizeof(g_dest_addr));
 g_dest_addr.sin_family = AF_INET;
 g_dest_addr.sin_port = htons(STREAM_PORT);
 if (inet_pton(AF_INET, STREAM_IP, &g_dest_addr.sin_addr) !=1) {
 std::cerr << "Invalid stream IP address\n";
 return false;
 }

 // connect to server
 std::cout << "Connecting to " << STREAM_IP << ":" << STREAM_PORT << " via TCP\n";
 int conn_res = connect(g_sock, (struct sockaddr*)&g_dest_addr, sizeof(g_dest_addr));
#ifdef _WIN32
 if (conn_res == SOCKET_ERROR) {
 std::cerr << "connect() failed: " << WSAGetLastError() << "\n";
 closesocket(g_sock);
 g_sock = INVALID_SOCKET;
 return false;
 }
#else
 if (conn_res <0) {
 std::cerr << "connect() failed\n";
 close(g_sock);
 g_sock = -1;
 return false;
 }
#endif

 std::cout << "Connected to " << STREAM_IP << ":" << STREAM_PORT << "\n";
 return true;
}

void close_stream() {
 if (
#ifdef _WIN32
 g_sock != INVALID_SOCKET
#else
 g_sock >=0
#endif
 ) {
 #ifdef _WIN32
 shutdown(g_sock, SD_BOTH);
 closesocket(g_sock);
 WSACleanup();
 g_sock = INVALID_SOCKET;
 #else
 shutdown(g_sock, SHUT_RDWR);
 close(g_sock);
 g_sock = -1;
 #endif
 }
}

int find_radiomaster_index() {
 int num = SDL_NumJoysticks();
 for (int i =0; i < num; ++i) {
 const char* name = SDL_JoystickNameForIndex(i);
 std::string sname = name ? name : "(unknown)";
 std::string lower = toLower(sname);
 if (lower.find(g_target_name) != std::string::npos || lower.find("radiomaster") != std::string::npos || lower.find("edgetx") != std::string::npos) {
 std::cout << "Found candidate joystick index=" << i << " name='" << sname << "'\n";
 return i;
 }
 }
 return -1;
}

int main(int argc, char** argv) {
 // Allow overriding target name via command line: --name "Radiomaster Pocket Joystick"
 for (int i =1; i < argc; ++i) {
 std::string a = argv[i];
 if (a == "--name" && i +1 < argc) {
 g_target_name = toLower(argv[++i]);
 std::cout << "Target device name set to: '" << g_target_name << "'\n";
 }
 }

 std::cout << "Starting Radiomaster-focused joystick scanner (SDL2)" << "\n";

 if (SDL_Init(SDL_INIT_JOYSTICK | SDL_INIT_EVENTS) !=0) {
 std::cerr << "SDL_Init error: " << SDL_GetError() << "\n";
 return 1;
 }

 SDL_JoystickEventState(SDL_ENABLE);

 // initialize TCP streaming
 if (!init_stream()) {
 std::cerr << "Warning: failed to initialize TCP stream. Continuing without network streaming.\n";
 }

 SDL_Joystick* joy = nullptr;
 int opened_instance_id = -1;
 int opened_device_index = -1;

 auto try_open = [&]() -> bool {
 int idx = find_radiomaster_index();
 if (idx <0) {
 std::cout << "Radiomaster not found in current device list." << "\n";
 return false;
 }
 std::cout << "Attempting to open Radiomaster at device index " << idx << "\n";

 // If we already have a joystick open and it's the same index, keep it
 if (joy && opened_device_index == idx) {
 return true;
 }

 // If another joystick is open, close it first
 if (joy) {
 std::cout << "Closing previously opened joystick (index " << opened_device_index << ")\n";
 SDL_JoystickClose(joy);
 joy = nullptr;
 opened_instance_id = -1;
 opened_device_index = -1;
 }

 SDL_Joystick* j = SDL_JoystickOpen(idx);
 if (!j) {
 std::cerr << "Failed to open joystick at index " << idx << ": " << SDL_GetError() << "\n";
 return false;
 }
 opened_device_index = idx;
 opened_instance_id = SDL_JoystickInstanceID(j);
 joy = j;
 const char* name = SDL_JoystickName(joy);

 // Print GUID for debugging (may differ per platform)
 SDL_JoystickGUID guid = SDL_JoystickGetGUID(joy);
 char guid_str[64];
 SDL_JoystickGetGUIDString(guid, guid_str, sizeof(guid_str));

 std::cout << "Opened joystick instance id " << opened_instance_id << " name='" << (name ? name : "(unknown)") << "' guid=" << guid_str << "\n";
 return true;
 };

 // Initial try
 try_open();

 bool running = true;
 // update interval set to100 ms
 auto last_print = std::chrono::steady_clock::now() - std::chrono::milliseconds(100);

 // Axis mapping according to actual device mapping: left stick uses axes2 (horizontal) and3 (vertical)
 // Right stick uses axes0 (horizontal) and1 (vertical)
 // The physical device reports axes with left stick on2/3 but the orientation was reversed,
 // left_x to the horizontal axis and left_y to the vertical axis.
 int left_x =3; // left joystick horizontal -> Yaw [-1,1]
 int left_y =2; // left joystick vertical -> Throttle [0,1]
 int right_x =0; // right joystick horizontal -> Roll [-1,1]
 int right_y =1; // right joystick vertical -> Pitch [-1,1]

 while (running) {
 SDL_Event ev;
 while (SDL_PollEvent(&ev)) {
 switch (ev.type) {
 case SDL_QUIT:
 running = false;
 break;
 case SDL_JOYDEVICEADDED:
 std::cout << "SDL event: JOYDEVICEADDED device index=" << ev.jdevice.which << "\n";
 if (!joy) try_open();
 break;
 case SDL_JOYDEVICEREMOVED:
 std::cout << "SDL event: JOYDEVICEREMOVED instance_id=" << ev.jdevice.which << "\n";
 if (joy) {
 int removed_id = ev.jdevice.which;
 if (removed_id == opened_instance_id) {
 std::cout << "Our Radiomaster was removed. Closing joystick." << "\n";
 SDL_JoystickClose(joy);
 joy = nullptr;
 opened_instance_id = -1;
 opened_device_index = -1;
 }
 }
 break;
 default:
 break;
 }
 }

 if (!joy) {
 static int attempt_count =0;
 if ((++attempt_count %10) ==0) try_open();
 std::this_thread::sleep_for(std::chrono::milliseconds(200));
 continue;
 }

 // Print JSON every100 ms (overwrite previous output)
 auto now = std::chrono::steady_clock::now();
 if (now - last_print >= std::chrono::milliseconds(100)) {
 last_print = now;
 int axes = SDL_JoystickNumAxes(joy);
 std::vector<Sint16> axis_values(axes);
 for (int a =0; a < axes; ++a) axis_values[a] = SDL_JoystickGetAxis(joy, a);

 // Build JSON output (manual)
 std::ostringstream ss;
 ss << std::fixed << std::setprecision(3);

 // device info
 const char* name = SDL_JoystickName(joy);
 SDL_JoystickGUID guid = SDL_JoystickGetGUID(joy);
 char guid_str[64];
 SDL_JoystickGetGUIDString(guid, guid_str, sizeof(guid_str));

 auto now_t = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
 char timebuf[64];
 std::strftime(timebuf, sizeof(timebuf), "%FT%T%z", std::localtime(&now_t));

 // Helpers
 auto safe_norm = [&](int idx) -> double {
 if (idx <0 || idx >= axes) return 0.0;
 return (double)normalizeAxis(axis_values[idx]);
 };
 auto throttle_mapped = [&](int idx) -> double {
 double n = safe_norm(idx);
 double m = (n +1.0) /2.0;
 if (m <0.0) m =0.0;
 if (m >1.0) m =1.0;
 return m;
 };

 double yaw = safe_norm(left_x);
 double throttle = throttle_mapped(left_y);
 double pitch = safe_norm(right_y);
 double roll = safe_norm(right_x);

 // Clear screen and move cursor to top (ANSI escape)
 std::cout << "\x1b[2J\x1b[H";

 // JSON: only numeric values (throttle in [0..1], others in [-1..1])
 ss << "{\n";
 ss << " \"device\": {\n";
 ss << " \"name\": \"" << (name ? name : "(unknown)") << "\",\n";
 ss << " \"index\": " << opened_device_index << ",\n";
 ss << " \"guid\": \"" << guid_str << "\",\n";
 ss << " \"timestamp\": \"" << timebuf << "\"\n";
 ss << " },\n";

 // Swap labels: left_stick shows yaw/throttle, right_stick shows pitch/roll
 ss << " \"left_stick\": {\n";
 ss << " \"yaw\": " << yaw << ",\n";
 ss << " \"throttle\": " << throttle << "\n";
 ss << " },\n";

 ss << " \"right_stick\": {\n";
 ss << " \"pitch\": " << pitch << ",\n";
 ss << " \"roll\": " << roll << "\n";
 ss << " }\n";

 ss << "}\n";

 std::string json = ss.str();

 // Create a single-line (newline-delimited) version of the JSON for network transport
 std::string net_json;
 net_json.reserve(json.size());
 for (char c : json) {
 if (c == '\n' || c == '\r') continue; // remove internal newlines
 net_json.push_back(c);
 }
 net_json.push_back('\n'); // message delimiter for the receiver

 // Human-readable block (labels swapped to match physical movement)
 std::ostringstream human;
 human << std::fixed << std::setprecision(3);
 human << "LEFT STICK:\n";
 human << " Yaw (axis " << left_x << "): " << yaw << "\n";
 human << " Throttle (axis " << left_y << "): mapped=[0..1] = " << throttle << "\n\n";
 human << "RIGHT STICK:\n";
 human << " Pitch (axis " << right_y << "): " << pitch << "\n";
 human << " Roll (axis " << right_x << "): " << roll << "\n";

 // Output JSON then human block
 std::cout << json << "\n" << human.str();

 // Send single-line JSON over TCP to configured address
 if (
#ifdef _WIN32
 g_sock != INVALID_SOCKET
#else
 g_sock >=0
#endif
 ) {
#ifdef _WIN32
 int sent = (int)send(g_sock, net_json.c_str(), (int)net_json.size(),0);
 if (sent == SOCKET_ERROR) {
 std::cerr << "send() failed: " << WSAGetLastError() << "\n";
 close_stream();
 }
#else
 ssize_t sent = send(g_sock, net_json.c_str(), (ssize_t)net_json.size(),0);
 if (sent <0) {
 std::cerr << "send() failed\n";
 close_stream();
 }
#endif
 }
 }

 std::this_thread::sleep_for(std::chrono::milliseconds(50));
 }

 if (joy) SDL_JoystickClose(joy);
 close_stream();
 SDL_Quit();
 return 0;
}
