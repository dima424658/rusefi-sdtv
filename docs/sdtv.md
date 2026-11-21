# Suzuki SDTV secondary throttle

SDTV drives the downstream STVA motor and closes the position loop with one
sensor on TPS2 primary. TPS1 measures the mechanical throttle. In TunerStudio,
select **Suzuki SDTV** in one H-bridge slot and choose the bridge control mode
for the ECU board. Verify the specified motor outputs and disable behavior for
the uaEFI hardware revision. The valve can return to its approximately 45°
mechanical rest with the motor disabled.

With the engine stopped, use **Calibrate TPS endpoints** and verify the valve
moves in the commanded direction. Burn the endpoints and confirm the reading
at both stops. Calibrate the position PID manually or use **Start SDTV PID
autotune**, then review the gains. Autotune needs the valve to start within
the configured travel limits.

The **Suzuki SDTV Opening** map uses RPM and either mechanical TPS1 (default)
or PPS. Tune the opening cells live in TunerStudio or via the ECU MCP map
tools. Configure axes, travel limits, and sensor and motor settings with the
engine stopped. The ECU uses calibrated percent open throughout.

Enable **Use SDTV for idle and cranking** only with the other idle actuators
unassigned and modeled-flow idle disabled. Set the idle travel range, then
tune the existing coolant-based cranking position and idle RPM settings. The
idle output spans the configured idle travel range. The driving map resumes
when the idle controller exits idle or the post-start taper.

SDTV mode is 0 when disabled, 1 on the driving map, 2 under idle or cranking
control, and 3 during PID autotune. A loss of valid feedback or demand, or a jam
during operation, disables bridge drive and invokes the existing 1,500 RPM
ETB fault limit. Verify the disabled position and fault response on the target
hardware before road use.
