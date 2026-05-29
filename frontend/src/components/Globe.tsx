import { useEffect, useRef } from 'react';
import { Viewer, Ion } from 'cesium';
import 'cesium/Build/Cesium/Widgets/widgets.css';

const cesiumToken = import.meta.env.VITE_CESIUM_ION_TOKEN;
if (cesiumToken) {
  Ion.defaultAccessToken = cesiumToken;
}

export default function Globe() {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewerRef = useRef<Viewer | null>(null);

  useEffect(() => {
    if (!containerRef.current) return;

    const viewer = new Viewer(containerRef.current, {
      animation: false,
      timeline: false,
      baseLayerPicker: false,
      geocoder: false,
      homeButton: false,
      sceneModePicker: false,
      navigationHelpButton: false,
      fullscreenButton: false,
      infoBox: false,
      selectionIndicator: false,
    });

    if (viewer.scene.skyBox) viewer.scene.skyBox.show = true;
    if (viewer.scene.sun) viewer.scene.sun.show = true;
    if (viewer.scene.moon) viewer.scene.moon.show = true;
    viewer.scene.globe.enableLighting = true;

    viewerRef.current = viewer;

    return () => {
      viewer.destroy();
      viewerRef.current = null;
    };
  }, []);

  return <div ref={containerRef} style={{ width: '100%', height: '100%' }} />;
}
