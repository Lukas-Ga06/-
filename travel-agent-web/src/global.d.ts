/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 高德开放平台「Web 端(JS API)」Key */
  readonly VITE_AMAP_KEY?: string;
  /** 与 Web 端 Key 配套的安全密钥 */
  readonly VITE_AMAP_SECURITY_CODE?: string;
  /** 足迹地图底图：默认 amap；设为 osm 可退回 OSM（含 OpenFreeMap 深色） */
  readonly VITE_FOOTPRINT_BASEMAP?: 'amap' | 'osm';
}

declare global {
  interface Window {
    SpeechRecognition?: any;
    webkitSpeechRecognition?: any;
  }
}

export {};
