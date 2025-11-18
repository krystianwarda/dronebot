

Package com.dronebot.adapters.hybridsimradio
Hybrid radio/autopilot adapters that let you switch at runtime between a real radio input and an autopilot (hover) 
stream using a space-bar toggle. All components are built on FS2 streams and topics.


hybridsimradio/AutopilotStub.scala
Type: Object AutopilotStub
Purpose: Simple autopilot stub that outputs a constant, neutral ControllerState at a fixed rate.

Input:
JoystickRanges to pick neutral centers for all axes.
Frequency hz (default 50 Hz).

Output:
stream: Stream[F, ControllerState] with neutral sticks and current timestamp.

Notes:
Useful as a placeholder or safety fallback autopilot when no real autopilot is wired.



hybridsimradio/HybridAutopilotWiring.scala
Type: Object HybridAutopilotWiring
Purpose: Build a HybridSimRadio instance wired to the hover autopilot and an optional telemetry receiver.

Input:
Dispatcher[F] (for other UI integration, not used directly here).
Control Topic[F, ControllerState].
Hybrid toggle Topic[F, Unit].
realRadioStream: Stream[F, ControllerState].
JoystickRanges.
Optional TelemetryUdpReceiver[F].
Target altitude targetZ.

Output:
HybridSimRadio[F] that merges real radio and hover autopilot.

Notes:
Creates or reuses a TelemetryUdpReceiver.
Uses HoverAutopilot.stream to produce an autopilot ControllerState stream.
Intended as the main wiring entrypoint for hybrid mode.



hybridsimradio/HybridMode.scala
Type: Sealed trait HybridMode with cases Manual and Auto.
Purpose: Represent and toggle between manual and autopilot control modes.

Input:
Current HybridMode.

Output:
toggle: HybridMode => HybridMode (switches Manual <-> Auto).

Notes:
Used by HybridSimRadio to decide which stream has control authority.



hybridsimradio/HybridSimRadio.scala
Type: Class HybridSimRadio[F[_]] and companion object.
Purpose: Runtime multiplexer that switches control authority between real radio and autopilot based on toggle events.

Input (class constructor):
Control Topic[F, ControllerState] (downstream consumers subscribe here).
Toggle Topic[F, Unit] (space-bar or other UI events).
realRadioStream: Stream[F, ControllerState].
autopilotStream: Stream[F, ControllerState].

Input (companion apply):
Dispatcher[F] (accepted for API compatibility, not currently used).
Same topics and streams as above.

Output:
stream: Stream[F, Unit] that:
Listens for toggle events and flips HybridMode.
Forwards either real or autopilot ControllerState into ctrlTopic based on current mode.

Notes:
Remembers the last real and last autopilot state to publish a smooth handover on mode change.
Logs mode switches to stdout.
Keeps internal state in Refs for mode and last states.



hybridsimradio/SpaceToggle.scala
Type: Object SpaceToggle
Purpose: Install a global JavaFX key handler that converts space-bar presses into hybrid toggle events.

Input:
Dispatcher[F].
Toggle Topic[F, Unit].

Output:
Effect F[Unit] that, when run, attaches a key handler to all current FX windows:
Publishes a unit event on space press.
Prints a log line when space is pressed.

Notes:
Uses Platform.runLater and Window.getWindows to attach handlers without a direct Scene reference.
Integrates with the global FS2-based event bus via the Topic.



hybridsimradio/TcpJsonServerRadio.scala
Type: Object TcpJsonServerRadio
Purpose: Expose radio commands as newline-delimited JSON over a TCP server for external tools or UIs.

Input:
Bind host and port.
Expected payload:
One JSON object per line, with numeric fields for:
t or throttle
y or yaw
p or pitch
r or roll

Output:
stream: Stream[F, ControllerState] parsed from each valid JSON line.

Notes:
Logs server binding, client connections, and parse errors.
Uses simple regex extraction instead of a full JSON parser.
Uses shared clamping helpers to constrain values to [0,1] (throttle) and [-1,1] (yaw, pitch, roll).
Emits a neutral ControllerState and logs when a line cannot be parsed.



hybridsimradio/TcpRadioStream.scala
Type: Object TcpRadioStream
Purpose: TCP client that subscribes to a simple text radio feed and converts it into ControllerState values.

Input:
Remote host and port.
Expected line formats (examples):
CSV: 0.50,0.00,0.10,-0.20 => t,y,p,r.
Key/value: t=0.50 y=0.00 p=0.10 r=-0.20.

Output:
stream: Stream[F, ControllerState] with clamped throttle, yaw, pitch, roll.

Notes:
Logs connection establishment and parse errors.
Uses the same clamping helpers as the JSON server.
Emits a neutral ControllerState and logs when a line cannot be parsed.
Designed as a lightweight test/sim input source that can feed into HybridSimRadio.






Package com.dronebot.adapters.simradio
Simulated radio and simulator output adapters used when running against a UI and/or local simulator instead of a physical radio. 

simradio/SimRadioControlInput.scala  
Type: Class SimRadioControlInput\[F\[_\]\] implementing ControlInputPort\[F\] and companion object.  
Purpose: Provide a simulated radio input source backed by FS2 topics for use by the UI and test tools.

Input:
- UI\-published ControllerState values via `publishControl`.
- `triggerCalibrate`, `triggerTest`, `triggerStop` calls from UI buttons.
- JoystickRanges configuration.

Output:
- `controllerStream`: `Stream[F, ControllerState]` subscribed from an internal topic.
- `Topic[F, Unit]` signals for calibration, test runs, and stop events (consumed by other components).

Notes:
- `make` allocates all topics and returns a ready\-to\-use adapter instance.
- The core is unaware whether input comes from a real radio or this simulator.
- Internally uses FS2 `Topic` to fan out controller and button events to multiple consumers.



simradio/Calibration.scala
Type: Class Calibration[F[_]].
Purpose: Drive a time-based joystick calibration routine using a scalafx.animation.AnimationTimer.

Input:
- Dispatcher[F] and Topic[F, Unit] to notify that calibration started.
- applyPositions: (Double, Double, Double, Double) => Unit callback to move virtual sticks.
- onFinish: () => Unit callback invoked when calibration ends.
  
Output:
- A repeatable sequence of stick positions over time (center, square pattern, ramps and holds).
- A single calibration-start event published on the topic.
  
Notes:
- Internal state machine (stepIndex) controls multi-step calibration with pauses and ramps.
- stop() stops the timer and calls onFinish.



simradio/ConsoleSimulatorOutput.scala
Type: Class ConsoleSimulatorOutput[F[_]] implementing SimulatorOutputPort[F].
Purpose: Simple simulator output that prints control commands to stdout.
  
Input:
- ControlCommand values from the core.
  
Output:
- Console log lines showing throttle, yaw, pitch, and roll.
  
Notes:
- Useful for debugging or running without any real simulator / vJoy backend.
  


simradio/VirtualGamepadPort.scala
Type: Trait VirtualGamepadPort[F[_]] and companion object.
Purpose: Optional backend that sends control commands to a virtual joystick device (vJoy).

Input:
- ControlCommand values.
- vJoy native DLLs located under C:\\tools\\DLLs.

Output:
- Axis updates on vJoy device id 1 (yaw, throttle, roll, pitch mapped to specific vJoy axes).
- No-op behavior if vJoy is not available or cannot be acquired.
  
Notes:
- select chooses a vJoy-based implementation if possible, otherwise a no-op backend.
- Values are clamped to [-1.0, 1.0] and converted to the vJoy integer axis range.



Package com.dronebot.adapters.simdroneinfo
Simulated drone telemetry adapters receive binary UDP packets from a simulator and expose them as FS2 streams of structured telemetry data for the core.

simdroneinfo/DroneTelemetry.scala
Type: Case classes, codec object, and class TelemetryUdpReceiver[F[_]: Async]
Purpose: Define the in-memory telemetry model, parse binary UDP packets into that model, and expose a stream of DroneTelemetry messages.

Input:
- Raw UDP datagrams containing a little-endian binary layout:
* Base block of 20 floats: timestamp, position, attitude, velocity, gyro, inputs, battery.
* Optional tail: motor_count byte followed by count floats of motor RPMs.
- UDP bind parameters (bindIp, port; default 0.0.0.0:9001).

Output:
- DroneTelemetryCodec.parse: Option[DroneTelemetry] parsed from a Array[Byte].
- TelemetryUdpReceiver.stream: Stream[F, DroneTelemetry] of decoded telemetry packets.

Notes:
- Swaps Y/Z for position and velocity so Z represents height / vertical speed.
- Ignores packets shorter than the base layout.
- Safely handles missing or truncated motor RPM data.




Package com.dronebot.adapters.radio
Radio adapters translate external joystick / radio input (typically JSON over TCP) into internal ControllerState values
and expose them as FS2 streams to the core.


radio/RadioInput.scala
Type: Object
Purpose: Entry point for selecting and starting a radio backend.

Input:
- JoystickRanges: configuration describing min\/max\/center for each axis.

Output:
- F\[Stream\[F, ControllerState\]\]: effect that yields a stream of controller states.

Notes:
- Currently hard-wired to use TcpServerRadio as the backend.
- This is the main function the core calls to obtain a ControlInputPort and its \`controllerStream\` as a \`Stream\[F, ControllerState\]\`.



radio/SocketRadio.scala
Type: Class SocketRadio\[F\[_\]: Async\] implementing ControlInputPort\[F\]
Purpose: Connects as a TCP client to a remote radio/joystick sender and converts incoming JSON into controller states.

Input:
- JoystickRanges configuration.
- TCP connection parameters (host, port; default 127.0.0.1:9000).
- Remote stream of JSON payloads over TCP.

Output: 
- controllerStream: Stream[F, ControllerState]
- infinite stream of controller states.

Notes:
- Manages a TCP client socket with automatic reconnection (via .repeat).
- Reads raw bytes, decodes UTF-8 text, uses SocketJson.extract and SocketJson.parse.
- Swallows read errors per chunk and terminates the current connection on failure.



radio/TcpServerRadio.scala
Type: Class TcpServerRadio\[F\[_\]: Async\] implementing ControlInputPort\[F\]
Purpose: Listens as a TCP server for a single remote radio/joystick client and converts incoming JSON into controller states.

Input:
- JoystickRanges configuration.
- TCP server parameters (host, port; default 127.0.0.1:9000).
- Incoming client connection sending JSON payloads over TCP.

Output:
- controllerStream: Stream[F, ControllerState]
- stream of controller states from the currently connected client, with retry on server errors.

Notes:
- Opens a reusable ServerSocket, handles BindException explicitly.
- For each accepted Socket, reads bytes, decodes UTF-8, and parses JSON.
- Fast path: newline-delimited JSON (per line).
- Fallback: brace-balanced extraction via SocketJson.extract for continuous streams.
- Cleans up server and client sockets using FS2 bracket and onFinalize.



radio/SocketJson.scala
Type: Package-private object
Purpose: Low-level JSON parsing utilities for radio input.

Input:
Raw text buffer that may contain ANSI escape codes and one or more JSON objects.
Individual JSON objects containing yaw, pitch, roll, throttle fields.
JoystickRanges to map normalized values into physical command ranges.

Output:
extract: (List[String], String) – a list of complete JSON objects and a remainder buffer.
parse: Option[ControllerState] – a parsed controller state for a single JSON object.

Notes:
Strips ANSI escape sequences.
Supports brace-balanced extraction for non-line-delimited streams.
Performs numeric range mapping from normalized [-1,1] / [0,1] into configured axis ranges.







Package com.dronebot.core.ports.inbound
Inbound ports define how the core receives external inputs: pilot controls, high-level commands, and telemetry.

inbound/ControlInputPort.scala
Type: Trait ControlInputPort\[F\[_\]\], case class ControlSource, trait GamepadInputPort\[F\[_\]\].
Purpose: Abstraction for real-time pilot control input streams.

Input:
- Hardware or simulated control sources (radio, gamepad, sim).
- JoystickRanges for scaling.

Output:
- controllerStream: Stream\[F, ControllerState\] (normalized throttle, yaw, pitch, roll, switches).

Notes:
- Implemented by adapters in radio, simradio, hybridsimradio, etc.
- Handles calibration, scaling, and failsafe at adapter level.

  


inbound/CommandPort.scala
Type: Class CommandPort.
Purpose: Interface placeholder for high-level mission/system commands (arm, mode changes, missions).

Input:
- External command sources (REST, CLI, scripts, message bus).

Output:
- Validated domain command objects made available to the core (planned).

Notes:
- Documentation describes responsibilities (validation, auth, rate limit, auditing); implementation is still a stub.


inbound/TelemetryPort.scala
Type: Class TelemetryPort.
Purpose: Interface placeholder for ingesting vehicle/simulator telemetry.

Input:
- Protocol-specific telemetry streams (e.g. MAVLink, custom UDP, serial).

Output:
- Domain telemetry snapshots / events provided to the core (planned).
  
Notes:
- Responsibilities include decoding, validation, aggregation, and lifecycle handling; currently a stub, with concrete behavior implemented in adapters like simdroneinfo.



Package com.dronebot.core.ports.outbound
Outbound ports define how the core sends commands to simulators or vehicles.

outbound/SimulatorOutputPort.scala
Type: Trait SimulatorOutputPort[F[_]].
Purpose: Abstraction for sending ControlCommand values to a simulator backend.

Input:
- ControlCommand instances produced by the flight control logic.

Output:
- send(command: ControlCommand): F[Unit] effect that transmits the command.

Notes:
- Implemented by adapters such as simradio/ConsoleSimulatorOutput and potential vJoy or network simulators.
- Handles protocol encoding, rate limiting, retries, and error reporting at adapter level.



Package com.dronebot.app
Application entrypoint, configuration loading, and runtime wiring of UI, devices, and FS2 streams.

app/Main.scala
Type: Object Main extends IOApp.Simple.
Purpose: Boot the application and compose all long-running streams.

Input:
- application.conf on classpath for joystick ranges and UI layout.
- Runtime events on AppBus topics (startHybrid, stop, etc.).
  Output:
- Running FS2 streams for gamepad output, telemetry input, hybrid radio, and UI.
- Application shutdown when stop topic emits.
  Notes:
- Loads AppConfig, creates Dispatcher, AppBus, DeviceManager, and UILayerFx.
- Uses parJoinUnbounded to run all programs concurrently.


Package com.dronebot.app.config
app/config/AppConfig.scala
Type: Case classes AxisRange, JoystickRanges, UiLayout, AppConfig and companion object.
Purpose: Define and load strongly typed application configuration via PureConfig.

Input:
- application.conf with joystick and ui sections.

Output:
- load[F]: F[AppConfig] containing joystick ranges and UI layout.

Notes:
- Axis ranges are used by radio/autopilot code; UI layout is used to size/theme the JavaFX UI.


Package com.dronebot.app.runtime
app/runtime/AppBus.scala
Type: Case class AppBus.
Purpose: In-process event bus implemented with FS2 Topics.

Input:
- Publications from UI and runtime programs (control states, button events, mode switches).

Output:
- Subscribable streams for control (ctrl), calibration/test/stop signals and mode selection (startRadio, startSim, startHybrid, autoToggle).

Notes:
- Central hub connecting UI, radio/sim adapters, and hybrid controller.


app/runtime/DeviceManager.scala
Type: Class DeviceManager, companion object.
Purpose: Lazily discover and cache a VirtualGamepadPort.

Input:
- Requests from programs needing a virtual gamepad.

Output:
- getOrCreate: IO[Option[VirtualGamepadPort[IO]]].

Notes:
- Uses a Ref to ensure the vJoy backend is initialized at most once.


app/runtime/Programs.scala
Type: Object Programs.
Purpose: Collection of top-level FS2 programs that wire core, adapters, and UI.

Input:
- AppBus, DeviceManager, Dispatcher, UILayerFx, config values, network bind parameters.

Output:
- gamepad: stream mirroring ControllerState to virtual gamepad and UI.
- telemetry: stream feeding UDP telemetry into the UI.
- radio: helper to publish radio states into ctrl topic and UI.
- hybrid: hybrid radio/autopilot stream using HybridSimRadio.
- uiMain: JavaFX UI lifecycle and global space-toggle handler.

Notes:
- Each program is composed into the main app in Main.run.