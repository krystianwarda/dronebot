└───com
└───dronebot
├───adapters
│   ├───db
│   │       Database.scala
│   │
│   ├───hybridsimradio
│   │       AutopilotStub.scala
│   │       HybridAutopilotWiring.scala
│   │       HybridMode.scala
│   │       HybridSimRadio.scala
│   │       SpaceToggle.scala
│   │       TcpJsonServerRadio.scala
│   │       TcpRadioCommon.scala
│   │       TcpRadioStream.scala
│   │
│   ├───radio
│   │       RadioInput.scala
│   │       SocketJson.scala
│   │       SocketRadio.scala
│   │       TcpServerRadio.scala
│   │
│   ├───realdroneinfo
│   ├───simdroneinfo
│   │       DroneTelemetry.scala
│   │
│   └───simradio
│           Calibration.scala
│           ConsoleSimulatorOutput.scala
│           SimRadioControlInput.scala
│           VirtualGamepadPort.scala
│
├───app
│   │   Main.scala
│   │
│   ├───config
│   │       AppConfig.scala
│   │
│   └───runtime
│           AppBus.scala
│           DeviceManager.scala
│           Programs.scala
│
├───core
│   ├───domain
│   │   ├───control
│   │   │       ControlInput.scala
│   │   │       ControlOutput.scala
│   │   │
│   │   └───flight
│   │           Axis.scala
│   │           DroneState.scala
│   │           FlightMode.scala
│   │
│   ├───flightcontrol
│   │   ├───autopilot
│   │   │       Autopilot.scala
│   │   │       HoverAutopilot.scala
│   │   │
│   │   ├───checks
│   │   │       FlightChecks.scala
│   │   │       PreFlightCheckResult.scala
│   │   │       TestFlightRunner.scala
│   │   │       VJoyRadioHealth.scala
│   │   │
│   │   ├───fsm
│   │   │       FlightStateMachine.scala
│   │   │
│   │   ├───management
│   │   │       FlightManager.scala
│   │   │
│   │   └───planner
│   │           TestFlight.scala
│   │
│   ├───ports
│   │   ├───inbound
│   │   │       CommandPort.scala
│   │   │       ControlInputPort.scala
│   │   │       TelemetryPort.scala
│   │   │
│   │   └───outbound
│   │           SimulatorOutputPort.scala
│   │
│   └───utils
│           MathUtil.scala
│
└───ui
    │   AltitudeGraphView.scala
    │   ControlsPanel.scala
    │   GameDroneInfoView.scala
    │   JoystickView.scala
    │   SimRadioUI.scala
    │   SimulatedGimbalView.scala
    │   TelemetryMapView.scala
    │   UILayer.scala
    │   UILayerPort.scala
    │
    └───dashboard
        CompassGauge.scala
        DashboardView.scala
        GaugeUtils.scala
        HorizonGauge.scala
        RpmGauge.scala
