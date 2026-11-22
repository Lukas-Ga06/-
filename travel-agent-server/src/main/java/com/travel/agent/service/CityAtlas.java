package com.travel.agent.service;

import com.travel.agent.util.CityNames;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 城市图集：常用城市 → 大致中心坐标（[lat, lng]）。
 * 用于首页足迹地图等轻量定位场景，秒级返回、无需联网；
 * 图集未收录的城市由 OnlineCityService 联网定位兜底。
 */
@Component
public class CityAtlas {

    private static final Map<String, double[]> CITIES = Map.ofEntries(
            Map.entry("北京", new double[]{39.9042, 116.4074}),
            Map.entry("上海", new double[]{31.2304, 121.4737}),
            Map.entry("广州", new double[]{23.1291, 113.2644}),
            Map.entry("深圳", new double[]{22.5431, 114.0579}),
            Map.entry("杭州", new double[]{30.2741, 120.1551}),
            Map.entry("南京", new double[]{32.0603, 118.7969}),
            Map.entry("成都", new double[]{30.5728, 104.0668}),
            Map.entry("重庆", new double[]{29.5630, 106.5516}),
            Map.entry("西安", new double[]{34.3416, 108.9398}),
            Map.entry("武汉", new double[]{30.5928, 114.3055}),
            Map.entry("天津", new double[]{39.3434, 117.3616}),
            Map.entry("洛阳", new double[]{34.6197, 112.4540}),
            Map.entry("石家庄", new double[]{38.0428, 114.5149}),
            Map.entry("郑州", new double[]{34.7466, 113.6254}),
            Map.entry("济南", new double[]{36.6512, 117.1201}),
            Map.entry("青岛", new double[]{36.0671, 120.3826}),
            Map.entry("大连", new double[]{38.9140, 121.6147}),
            Map.entry("沈阳", new double[]{41.8057, 123.4315}),
            Map.entry("哈尔滨", new double[]{45.8038, 126.5350}),
            Map.entry("长春", new double[]{43.8171, 125.3235}),
            Map.entry("长沙", new double[]{28.2282, 112.9388}),
            Map.entry("南昌", new double[]{28.6820, 115.8579}),
            Map.entry("合肥", new double[]{31.8206, 117.2272}),
            Map.entry("福州", new double[]{26.0745, 119.2965}),
            Map.entry("厦门", new double[]{24.4798, 118.0894}),
            Map.entry("宁波", new double[]{29.8683, 121.5440}),
            Map.entry("苏州", new double[]{31.2989, 120.5853}),
            Map.entry("无锡", new double[]{31.4912, 120.3119}),
            Map.entry("常州", new double[]{31.8107, 119.9740}),
            Map.entry("南通", new double[]{31.9802, 120.8943}),
            Map.entry("温州", new double[]{28.0000, 120.6994}),
            Map.entry("绍兴", new double[]{30.0300, 120.5804}),
            Map.entry("嘉兴", new double[]{30.7465, 120.7555}),
            Map.entry("台州", new double[]{28.6560, 121.4208}),
            Map.entry("金华", new double[]{29.0789, 119.6497}),
            Map.entry("泉州", new double[]{24.8741, 118.6757}),
            Map.entry("昆明", new double[]{24.8801, 102.8329}),
            Map.entry("贵阳", new double[]{26.6470, 106.6302}),
            Map.entry("南宁", new double[]{22.8170, 108.3665}),
            Map.entry("桂林", new double[]{25.2742, 110.2902}),
            Map.entry("张家界", new double[]{29.1176, 110.4793}),
            Map.entry("丽江", new double[]{26.8721, 100.2300}),
            Map.entry("大理", new double[]{25.6930, 100.1627}),
            Map.entry("西双版纳", new double[]{22.0017, 100.7979}),
            Map.entry("海口", new double[]{20.0444, 110.1999}),
            Map.entry("三亚", new double[]{18.2528, 109.5119}),
            Map.entry("太原", new double[]{37.8706, 112.5489}),
            Map.entry("兰州", new double[]{36.0611, 103.8343}),
            Map.entry("西宁", new double[]{36.6171, 101.7782}),
            Map.entry("银川", new double[]{38.4872, 106.2309}),
            Map.entry("乌鲁木齐", new double[]{43.8256, 87.6168}),
            Map.entry("拉萨", new double[]{29.6520, 91.1721}),
            Map.entry("呼和浩特", new double[]{40.8415, 111.7519}),
            Map.entry("佛山", new double[]{23.0219, 113.1220}),
            Map.entry("东莞", new double[]{23.0207, 113.7518}),
            Map.entry("珠海", new double[]{22.2710, 113.5767}),
            Map.entry("中山", new double[]{22.5158, 113.3926}),
            Map.entry("惠州", new double[]{23.1115, 114.4162}),
            Map.entry("汕头", new double[]{23.3540, 116.6820}),
            Map.entry("湛江", new double[]{21.2707, 110.3594}),
            Map.entry("北海", new double[]{21.4811, 109.1197}),
            Map.entry("徐州", new double[]{34.2044, 117.2857}),
            Map.entry("扬州", new double[]{32.3947, 119.4126}),
            Map.entry("镇江", new double[]{32.1886, 119.4252}),
            Map.entry("泰州", new double[]{32.4553, 119.9230}),
            Map.entry("秦皇岛", new double[]{39.9355, 119.5977}),
            Map.entry("承德", new double[]{40.9551, 117.9634}),
            Map.entry("大同", new double[]{40.0768, 113.3001}),
            Map.entry("敦煌", new double[]{40.1422, 94.6616}),
            Map.entry("嘉峪关", new double[]{39.7724, 98.2894}),
            Map.entry("延安", new double[]{36.5855, 109.4896}),
            Map.entry("遵义", new double[]{27.7255, 106.9273}),
            Map.entry("绵阳", new double[]{31.4680, 104.6864}),
            Map.entry("宜昌", new double[]{30.6920, 111.2865}),
            Map.entry("襄阳", new double[]{32.0424, 112.1220}),
            Map.entry("岳阳", new double[]{29.3571, 113.1290}),
            Map.entry("烟台", new double[]{37.4638, 121.4479}),
            Map.entry("威海", new double[]{37.5128, 122.1201}),
            Map.entry("潍坊", new double[]{36.7069, 119.1618}),
            Map.entry("临沂", new double[]{35.1041, 118.3564}),
            Map.entry("日照", new double[]{35.4164, 119.5269}),
            Map.entry("唐山", new double[]{39.6304, 118.1802}),
            Map.entry("保定", new double[]{38.8740, 115.4646}),
            Map.entry("廊坊", new double[]{39.5247, 116.7036}),
            Map.entry("邯郸", new double[]{36.6259, 114.5391}),
            Map.entry("开封", new double[]{34.7971, 114.3076}),
            Map.entry("安阳", new double[]{36.0997, 114.3927}),
            Map.entry("芜湖", new double[]{31.3528, 118.4327}),
            Map.entry("九江", new double[]{29.7052, 115.9930}),
            Map.entry("赣州", new double[]{25.8310, 114.9350}),
            Map.entry("平遥", new double[]{37.1895, 112.1763}),
            Map.entry("婺源", new double[]{29.2484, 117.8624}),
            Map.entry("千岛湖", new double[]{29.6045, 119.0353}),
            Map.entry("普陀山", new double[]{29.9741, 122.3826}),
            Map.entry("黄山", new double[]{29.7147, 118.1694}),
            Map.entry("武夷山", new double[]{27.7567, 118.0336}),
            Map.entry("恩施", new double[]{30.2721, 109.4881}),
            Map.entry("稻城", new double[]{29.0376, 100.2982}));

    /**
     * 解析城市坐标：名称归一（去“市/地区/特别行政区/自治州”后缀）后精确匹配；
     * 未精确命中时走共享匹配规则 {@link CityNames#longestMatch}（如 “杭州西湖” → 杭州）。
     *
     * <p>旧实现是无长度守卫的双向包含，导致 resolve("阳") 命中「洛阳」；
     * 现在单字一律不命中，等长多解也不猜（返回 null，交给上层兜底）。
     */
    public double[] resolve(String name) {
        String key = CityNames.normalize(name);
        if (key.isEmpty()) {
            return null;
        }
        double[] direct = CITIES.get(key);
        if (direct != null) {
            return direct;
        }
        String matched = CityNames.longestMatch(key, CITIES.keySet());
        return matched == null ? null : CITIES.get(matched);
    }
}
