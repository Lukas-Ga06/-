import type { Page } from '@playwright/test';

/**
 * 闅旂 e2e 閲岀殑楂樺痉 SDK 鏇胯韩銆? *
 * 涓轰粈涔堥渶瑕佸畠锛氶殧绂诲悗绔叧闂簡 AMAP锛屾瀯寤轰骇鐗╀篃娌℃湁 `VITE_AMAP_KEY`锛岀湡瀹?SDK 鎷変笉涓嬫潵锛? * 鍦板浘鍙細鏄剧ず澶辫触鎬佲€斺€旈偅鏍枫€岀偣鏍囪鍥炲啓鏃堕棿绾裤€嶈繖鏉¤矾寰勫湪 e2e 閲屾牴鏈病鏈夊彲鐐圭殑涓滆タ銆? * 绗笁鏂?SDK 鏄郴缁熻竟鐣岋紝杩欓噷鍦ㄨ竟鐣屼笂椤舵浛锛? *
 * - `MapView`/`AMapProvider` 鍙敤鍒?SDK 鐨勫皯鏁版柟娉曪紙on/add/remove/setCenter/setZoomAndCenter鈥︼級锛? *   鏇胯韩鎸夊悓鏍风殑濂戠害瀹炵幇锛屽苟鎶婄浉鏈鸿皟鐢ㄨ鍒?`window.__amapFake`锛屼緵銆屾病鏈夊姩缂╂斁銆嶈繖绫绘柇瑷€璇诲彇锛? * - `Marker.content` 鏄簲鐢ㄨ嚜宸辨瀯寤虹殑鐪熷疄 DOM锛坄.m3d-marker`锛夛紝鏇胯韩鎶婂畠鎸傝繘瀹瑰櫒锛? *   鍥犳銆岀偣鏍囪銆嶈蛋鐨勬槸搴旂敤鐪熷疄鐨勭偣鍑荤洃鍚摼璺紝鑰屼笉鏄祴璇曠洿鎺ヨ皟 provider锛? * - 涓嶄吉閫犱换浣曞簲鐢ㄧ姸鎬侊細閫変腑鎬佷粛鐒跺彧鐢卞簲鐢ㄥ啓 `uiStore.selectedElement`銆? *
 * 鍙湁鍦?`window.AMap` 灏氭湭瀛樺湪鏃舵墠娉ㄥ叆锛屼笉浼氳鐩栫湡瀹?SDK銆? */
export const FAKE_AMAP_SDK = `
(() => {
  if (window.AMap && window.AMap.__fake) return;
  const state = {
    maps: [],
    setCenterCalls: 0,
    setZoomAndCenterCalls: 0,
    zoom: null,
    center: null,
    centerFromSetCenter: 0,
  };
  window.__amapFake = state;

  function FakeMap(container, options) {
    this.container = container;
    this.options = options || {};
    this.zoom = typeof this.options.zoom === 'number' ? this.options.zoom : 11;
    this.center = this.options.center || [0, 0];
    this.handlers = {};
    
    state.maps.push(this);
    state.zoom = this.zoom;
    state.center = this.center;
    this.layer = document.createElement('div');
    this.layer.className = 'fake-amap-layer';
    this.layer.style.position = 'absolute';
    this.layer.style.inset = '0';
    container.style.position = 'relative';
    container.appendChild(this.layer);
    const self = this;
    // SDK 鐨?complete 鏄紓姝ヤ簨浠讹細搴旂敤鎹鎶婂簳鍥炬爣璁颁负 ok
    setTimeout(function () {
      (self.handlers.complete || []).forEach(function (fn) { fn(); });
    }, 0);
  }
  FakeMap.prototype.on = function (event, handler) {
    this.handlers[event] = this.handlers[event] || [];
    this.handlers[event].push(handler);
  };
  FakeMap.prototype.add = function (overlays) {
    const list = Array.isArray(overlays) ? overlays : [overlays];
    const self = this;
    list.forEach(function (overlay) {
      if (overlay && typeof overlay.__mount === 'function') overlay.__mount(self.layer, self);
    });
  };
  FakeMap.prototype.remove = function (overlays) {
    const list = Array.isArray(overlays) ? overlays : [overlays];
    list.forEach(function (overlay) {
      if (overlay && typeof overlay.__unmount === 'function') overlay.__unmount();
    });
  };
  FakeMap.prototype.addControl = function () {};
  FakeMap.prototype.setFitView = function () {};
  FakeMap.prototype.getZoom = function () { return this.zoom; };
  FakeMap.prototype.setZoomAndCenter = function (zoom, center) {
    this.zoom = zoom;
    this.center = center;
    state.zoom = zoom;
    state.center = center;
    state.setZoomAndCenterCalls += 1;
  };
  FakeMap.prototype.setCenter = function (center) {
    this.center = center;
    state.center = center;
    state.setCenterCalls += 1;
    state.centerFromSetCenter += 1;
  };
  FakeMap.prototype.setPitch = function () {};
  FakeMap.prototype.setRotation = function () {};
  FakeMap.prototype.getRotation = function () { return 0; };
  FakeMap.prototype.setMapStyle = function () {};
  FakeMap.prototype.resize = function () {};
  FakeMap.prototype.destroy = function () {
    if (this.layer && this.layer.parentNode) this.layer.parentNode.removeChild(this.layer);
  };

  function FakeMarker(options) {
    this.options = options || {};
    this.el = null;
  }
  FakeMarker.prototype.__mount = function (layer, map) {
    const el = this.options.content;
    if (!el) return;
    // 鎸夊綋鍓嶅眰鍐呮爣璁版暟鎺掑竷锛氶噸缁橈紙鍏堝叏閮ㄧЩ闄ゅ啀閲嶆柊娣诲姞锛夊悗浣嶇疆浠庡ご寮€濮嬶紝涓嶄細瓒婃帓瓒婅繙
    const index = layer.children.length;
    // 鐪熷疄 SDK 鎸夊潗鏍囨憜鏀炬爣璁帮紱鏇胯韩娌℃湁鎶曞奖锛岀敤鍥哄畾缃戞牸鎶婃爣璁版憡寮€锛屼繚璇佹瘡涓兘鑳借鐐瑰埌
    el.style.position = 'absolute';
    el.style.left = (20 + (index % 4) * 64) + 'px';
    el.style.top = (140 + Math.floor(index / 4) * 64) + 'px';
    this.el = el;
    layer.appendChild(el);
  };
  FakeMarker.prototype.__unmount = function () {
    if (this.el && this.el.parentNode) this.el.parentNode.removeChild(this.el);
  };

  function FakePolyline(options) {
    this.options = options || {};
  }

  function NoopControl() {}

  window.AMap = {
    __fake: true,
    Map: FakeMap,
    Marker: FakeMarker,
    Polyline: FakePolyline,
    Scale: NoopControl,
    ToolBar: NoopControl,
    ControlBar: NoopControl,
  };
})();
`;

/** 瀹夎鏇胯韩 SDK銆傚繀椤诲湪 `page.goto` 涔嬪墠璋冪敤锛坅ddInitScript 浼氬湪姣忎釜鏂囨。閲屽厛鎵ц锛夈€?*/
export async function installFakeAMap(page: Page): Promise<void> {
  await page.addInitScript({ content: FAKE_AMAP_SDK });
}

export interface FakeAMapState {
  setCenterCalls: number;
  setZoomAndCenterCalls: number;
  zoom: number | null;
  center: [number, number] | null;
}

/** 璇诲彇鏇胯韩璁板綍鐨勭浉鏈鸿皟鐢紝鐢ㄤ簬鏂█銆屽彧骞崇Щ銆佷笉鍔ㄧ缉鏀俱€?*/
export function readFakeAMap(page: Page): Promise<FakeAMapState> {
  return page.evaluate(() => {
    const fake = (window as unknown as { __amapFake?: FakeAMapState }).__amapFake;
    return {
      setCenterCalls: fake?.setCenterCalls ?? 0,
      setZoomAndCenterCalls: fake?.setZoomAndCenterCalls ?? 0,
      zoom: fake?.zoom ?? null,
      center: fake?.center ?? null,
    };
  });
}
