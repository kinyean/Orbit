# Software Requirements Specification
## Inter-Satellite Remote Proximity Operations Visualization and Simulation Platform

**Document Status:** Draft v0.1
**Date:** 28 May 2026

---

## 1. Introduction

### 1.1 Purpose
1.1.1 This document specifies the functional and non-functional requirements for a web-based platform to visualize and simulate inter-satellite Remote Proximity Operations (RPO).
1.1.2 The platform shall serve as an analysis, planning, rehearsal, and demonstration tool for missions involving close-range operations between two or more spacecraft.

### 1.2 Scope
1.2.1 The platform shall support RPO scenarios spanning far-range rendezvous through close-range proximity operations and inspection.
1.2.2 Operational regimes covered shall include co-elliptic phasing, Hohmann transfers, V-bar and R-bar approaches, hold points, natural motion circumnavigation, walking safety ellipses, and sensor-constrained inspection.

### 1.3 Definitions and Acronyms
1.3.1 **ECI** — Earth-Centered Inertial reference frame (J2000 / ICRF).
1.3.2 **ECEF** — Earth-Centered Earth-Fixed reference frame (ITRF).
1.3.3 **LVLH** — Local Vertical Local Horizontal frame.
1.3.4 **RIC** — Radial / In-track / Cross-track frame.
1.3.5 **RPO** — Remote Proximity Operations.
1.3.6 **TLE** — Two-Line Element set.
1.3.7 **CW** — Clohessy-Wiltshire equations.
1.3.8 **NMC** — Natural Motion Circumnavigation.
1.3.9 **FOV** — Field of View.
1.3.10 **CZML** — Cesium Markup Language.
1.3.11 **OEM / OPM / AEM** — CCSDS Orbit Ephemeris / Orbit Parameter / Attitude Ephemeris Message.

### 1.4 Stakeholders
1.4.1 Mission planners, flight dynamics engineers, GN&C analysts, and operations personnel involved in RPO mission design, analysis, rehearsal, and stakeholder engagement.

---

## 2. System Overview

### 2.1 System Context
2.1.1 The platform shall comprise four primary components: a backend propagation and analysis service, a frontend dual-viewport visualization client, a scenario data store, and external data interfaces.

### 2.2 Operational Concept
2.2.1 The platform shall accept a scenario definition consisting of one designated chief spacecraft, one or more deputies, initial states, maneuver plan, attitude profiles, and sensor configurations.
2.2.2 The platform shall propagate state forward and backward in time and render synchronized global and proximity views with full playback control.

---

## 3. Functional Requirements

### 3.1 Orbital Dynamics and Propagation
3.1.1 The system shall support SGP4 / SDP4 propagation from TLE inputs.
3.1.2 The system shall support high-fidelity numerical propagation using an adaptive-step integrator (Dormand-Prince 8(7) or equivalent).
3.1.3 The high-fidelity propagator shall model non-spherical gravity to at least degree and order J4.
3.1.4 The high-fidelity propagator shall model atmospheric drag using NRLMSISE-00 or equivalent density model.
3.1.5 The high-fidelity propagator shall model solar radiation pressure with configurable spacecraft area-to-mass ratio and reflectivity coefficient.
3.1.6 The high-fidelity propagator shall model third-body perturbations from the Sun and Moon.
3.1.7 The system shall support linearized relative motion propagation via Clohessy-Wiltshire equations for close-range regimes.
3.1.8 The system shall allow per-scenario selection of propagator fidelity.

### 3.2 Reference Frame Management
3.2.1 The system shall support ECI (J2000 / ICRF).
3.2.2 The system shall support ECEF (ITRF) with polar motion and UT1-UTC corrections.
3.2.3 The system shall support LVLH and RIC frames centered on the designated chief spacecraft.
3.2.4 The system shall support per-spacecraft body-fixed frames driven by attitude quaternions.
3.2.5 All state vectors within the system shall be tagged with their reference frame.
3.2.6 The system shall provide a single canonical transformation utility for all inter-frame conversions.

### 3.3 Time System
3.3.1 The system shall maintain a single authoritative simulation clock.
3.3.2 The system shall support UTC with leap-second handling.
3.3.3 The system shall use J2000 epoch for ECI computations.
3.3.4 The system shall support time scaling from 0.01x to 10000x real time, including reverse playback.
3.3.5 The system shall support arbitrary time scrubbing within the scenario time range.

### 3.4 Relative Motion Modeling
3.4.1 The system shall compute and display relative state (position and velocity) of each deputy with respect to the chief in the chief's LVLH frame.
3.4.2 The system shall support closed-form propagation of relative motion via CW equations for initial analysis.
3.4.3 The system shall render trajectories in both inertial and relative frames.
3.4.4 The system shall display past and predicted trajectory segments with visually distinct styling.

### 3.5 Maneuver Modeling
3.5.1 The system shall support impulsive delta-V maneuvers applied at a specified epoch.
3.5.2 The system shall support finite-burn maneuvers parameterized by thrust magnitude, specific impulse, and duration.
3.5.3 The system shall provide pre-defined maneuver templates for: Hohmann transfer, two-impulse rendezvous, glideslope approach, V-bar hold, R-bar hold, NMC ellipse establishment, and station-keeping.
3.5.4 The system shall render each maneuver as a vector annotation in the relative frame at the maneuver epoch.
3.5.5 The system shall compute and display cumulative delta-V budget per spacecraft.

### 3.6 Sensor and Payload Modeling
3.6.1 The system shall represent payload sensors as first-class scenario objects with configurable type (optical, RF, lidar, radar).
3.6.2 The system shall model sensor field-of-view as cone, frustum, or arbitrary polygonal geometry.
3.6.3 The system shall model sensor minimum and maximum range limits.
3.6.4 The system shall model sensor pointing as body-fixed or gimbaled, with attitude and gimbal slew profiles.
3.6.5 The system shall compute and visualize occlusion of sensor lines-of-sight against other spacecraft, Earth, and the Sun.
3.6.6 The system shall provide a sensor-frame rendering of the scene from the perspective of any selected sensor.

### 3.7 Environment Modeling
3.7.1 The system shall compute and display Sun and Moon positions at simulation time.
3.7.2 The system shall compute and display Earth eclipse (umbra and penumbra) periods for each spacecraft.
3.7.3 The system shall illuminate spacecraft models in the proximity view consistent with the Sun vector at simulation time.
3.7.4 The system shall optionally render Earth atmosphere, day-night terminator, and city lights in the global view.

### 3.8 Visualization — Global View
3.8.1 The global view shall render Earth at WGS84 scale using CesiumJS.
3.8.2 The global view shall render orbital paths and current positions of all scenario spacecraft.
3.8.3 The global view shall render ground tracks for each spacecraft.
3.8.4 The global view shall support free camera control and pre-defined camera modes (Earth-fixed, inertial, chase, top-down).
3.8.5 The global view shall consume time-dynamic state data in CZML format.

### 3.9 Visualization — Proximity View
3.9.1 The proximity view shall render the close-range scene in the chief's LVLH frame using three.js or an equivalent WebGL framework.
3.9.2 The proximity view shall display the chief at the frame origin with deputies at their relative positions.
3.9.3 The proximity view shall render spacecraft 3D models with articulable elements (solar arrays, antennas, docking ports, thrusters).
3.9.4 The proximity view shall render sensor FOV volumes as translucent geometry.
3.9.5 The proximity view shall render trajectory ribbons for relative motion, distinguishing past and predicted segments.
3.9.6 The proximity view shall render delta-V vectors at maneuver epochs.
3.9.7 The proximity view shall support adjustable scale spanning ranges from 1 m to 100 km between spacecraft.
3.9.8 The proximity view shall support free camera control and pre-defined modes (chief-body, deputy-body, sensor-frame, fixed external).

### 3.10 Scenario Management
3.10.1 The system shall persist scenarios as serializable artifacts including initial states, maneuver plans, sensor configurations, and attitude profiles.
3.10.2 The system shall support scenario creation, editing, duplication, and deletion.
3.10.3 The system shall support import of initial states from TLE, CCSDS OEM, and CCSDS OPM formats.
3.10.4 The system shall support import of initial states from Keplerian elements.
3.10.5 The system shall support versioning of scenarios with author and timestamp metadata.

### 3.11 Simulation Control
3.11.1 The system shall provide play, pause, step-forward, step-backward, and reset controls.
3.11.2 The system shall provide a timeline scrub bar showing scenario extent and current simulation time.
3.11.3 The timeline shall be annotated with maneuver epochs, eclipse periods, sensor acquisition windows, and conjunction events.
3.11.4 The system shall maintain synchronization between global and proximity views at all times.

### 3.12 Events and Analysis
3.12.1 The system shall detect and log conjunctions below a configurable miss-distance threshold.
3.12.2 The system shall detect and log sensor acquisition and loss-of-sight events.
3.12.3 The system shall detect and log constraint violations including approach corridor, sun-keep-out angle, and plume impingement.
3.12.4 The system shall support Monte Carlo dispersion analysis on initial state uncertainty and maneuver execution error.
3.12.5 The system shall render navigation covariance ellipsoids in the relative frame.
3.12.6 The system shall compute and display link budget and signal-to-noise overlays for RF and optical sensors.

---

## 4. External Interfaces

### 4.1 Data Ingestion
4.1.1 The system shall ingest TLE data via file upload or URL retrieval (e.g., Celestrak, Space-Track).
4.1.2 The system shall ingest CCSDS OEM and OPM files.
4.1.3 The system shall ingest custom attitude profiles in CCSDS AEM format.

### 4.2 Data Export
4.2.1 The system shall export propagated ephemerides in CCSDS OEM format.
4.2.2 The system shall export scenario events in JSON and CSV formats.
4.2.3 The system shall export rendered views as PNG snapshots and MP4 video sequences.

### 4.3 Application Programming Interface
4.3.1 The backend shall expose a REST API for scenario CRUD operations.
4.3.2 The backend shall expose a WebSocket interface for streaming time-synchronized state updates to the frontend.
4.3.3 The API shall be documented per OpenAPI 3.x.

### 4.4 User Interface
4.4.1 The frontend shall present global view, proximity view, timeline, and scenario control panels in a configurable layout.
4.4.2 The frontend shall support modern desktop browsers (Chrome, Firefox, Edge, Safari) at their two most recent major versions.

---

## 5. Non-Functional Requirements

### 5.1 Performance
5.1.1 The proximity view shall sustain 60 frames per second with up to 10 spacecraft visible on reference hardware (mid-range discrete GPU, 16 GB RAM).
5.1.2 The global view shall sustain 30 frames per second under the same conditions.
5.1.3 Time-scrubbing latency from user input to rendered frame shall not exceed 200 ms.
5.1.4 Scenario load time shall not exceed 5 seconds for scenarios of 24-hour duration.

### 5.2 Accuracy
5.2.1 SGP4 propagation shall conform to the AIAA 2006-6753 reference implementation.
5.2.2 High-fidelity propagation shall achieve sub-kilometer position accuracy over 24 hours of LEO propagation relative to a validated reference solution.
5.2.3 CW relative-motion propagation shall achieve sub-meter accuracy over 1 hour for separations under 10 km.
5.2.4 Frame transformations shall preserve numerical precision to at least 1e-9 in normalized vector components.

### 5.3 Extensibility
5.3.1 New propagator implementations shall be addable without modification to the rendering layer.
5.3.2 New sensor types and maneuver primitives shall be addable through a plugin interface.
5.3.3 The visualization layer shall be decoupled from the propagation engine via a defined state-streaming contract.

### 5.4 Reliability and Reproducibility
5.4.1 A given scenario shall produce bit-identical propagation results across runs on the same platform version.
5.4.2 The system shall log all scenario modifications with timestamp and user identity.

### 5.5 Security
5.5.1 The system shall authenticate users via SSO or equivalent identity provider integration.
5.5.2 The system shall enforce role-based access control on scenario read, write, and execute operations.
5.5.3 All API traffic shall be transported over TLS 1.2 or higher.

### 5.6 Usability
5.6.1 A new user shall be able to load a sample scenario and play it back without prior training.
5.6.2 The system shall provide contextual help and tooltips for all primary controls.

---

## 6. Architecture Constraints

### 6.1 Technology Stack
6.1.1 The global-view rendering shall be implemented using CesiumJS.
6.1.2 The proximity-view rendering shall be implemented using three.js or an equivalent WebGL framework.
6.1.3 The two viewports shall share a single simulation clock and a single authoritative ephemeris service.
6.1.4 The backend shall expose propagation and scenario services independently of the visualization layer.

### 6.2 Deployment
6.2.1 The system shall be deployable as containerized services (Docker or equivalent).
6.2.2 The system shall support both cloud and on-premises deployment.

---

## 7. Out of Scope (v1)
7.1 Hardware-in-the-loop integration with flight software.
7.2 Real-time telemetry ingestion from operational spacecraft.
7.3 Trajectory optimization solvers (delta-V minimization, time-optimal transfers).
7.4 Multi-user collaborative editing of a single scenario in real time.

---

*End of document.*