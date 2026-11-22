/**
 * 一句话需求解析（方案 P1-02 / §7.1）。
 *
 * 背景：`/planner?prompt=...` 此前把整句话塞进「特别要求」，用户仍要在表单里
 * 重复填写目的地、天数、人数和预算。这里把自然语言里的结构化信息抽出来回填，
 * 只把**没被字段吸收**的部分留在特别要求里。
 *
 * 设计约束：
 * - 纯函数，不碰 DOM、不发请求，便于穷举单测；
 * - 每个字段带置信度：高置信默认采纳，低置信（如「周末」→ 2 天、人均预算）在界面上标注待确认；
 * - 冲突不静默覆盖：「两天」与「11月21日-11月23日」同时出现时记录冲突，由用户决定；
 * - 解析失败必须无害：matched=false 时调用方原样保留用户原话。
 */
import type { PlanFormState } from './planForm';
import { INTEREST_OPTIONS } from './planForm';

/** 字段级置信度：missing 表示没提到，low 表示提到了但需要用户确认 */
export type FieldConfidence = 'high' | 'low' | 'missing';

export interface ParsedPlanPrompt {
  destination: string | null;
  days: number | null;
  startDate: string | null;
  endDate: string | null;
  adults: number;
  kids: number;
  budget: number | null;
  interests: string[];
  confidence: {
    destination: FieldConfidence;
    days: FieldConfidence;
    people: FieldConfidence;
    budget: FieldConfidence;
    dates: FieldConfidence;
  };
  /** 天数与日期跨度互相矛盾的说明（为空表示没有冲突） */
  conflicts: string[];
  /** 解析后仍未被字段吸收的原文（进入「特别要求」） */
  leftovers: string;
  /** 是否识别到任何结构化字段 */
  matched: boolean;
}

const CN_DIGITS: Record<string, number> = {
  零: 0,
  一: 1,
  二: 2,
  两: 2,
  三: 3,
  四: 4,
  五: 5,
  六: 6,
  七: 7,
  八: 8,
  九: 9,
  十: 10,
};

const DAY_TOKEN = '(?:[零一二两三四五六七八九十\\d]{1,3}\\s*)?(?:天|日游)';

/** 中文/阿拉伯数字 → number（支持「十一」「二十三」） */
function toNumber(raw: string | undefined): number | null {
  if (!raw) return null;
  const trimmed = raw.trim();
  if (/^\d+(\.\d+)?$/.test(trimmed)) return Number(trimmed);
  if (trimmed.length === 1 && CN_DIGITS[trimmed] !== undefined) return CN_DIGITS[trimmed];
  const tenIdx = trimmed.indexOf('十');
  if (tenIdx >= 0) {
    const head = trimmed.slice(0, tenIdx);
    const tail = trimmed.slice(tenIdx + 1);
    const tens = head ? (CN_DIGITS[head] ?? 0) : 1;
    const ones = tail ? (CN_DIGITS[tail] ?? 0) : 0;
    if (tens === 0 && ones === 0) return null;
    return tens * 10 + ones;
  }
  return null;
}

const CITY_STOPWORDS = new Set([
  '周末',
  '假期',
  '春节',
  '国庆',
  '暑假',
  '寒假',
  '小长假',
  '这里',
  '那边',
  '附近',
  '周边',
  '国内',
  '国外',
]);

/** 没有任何句式线索时只靠城市名兜底，且只能给低置信 */
const KNOWN_CITIES = [
  '北京',
  '上海',
  '广州',
  '深圳',
  '杭州',
  '苏州',
  '南京',
  '成都',
  '重庆',
  '西安',
  '长沙',
  '武汉',
  '厦门',
  '青岛',
  '大连',
  '天津',
  '昆明',
  '大理',
  '丽江',
  '三亚',
  '海口',
  '桂林',
  '敦煌',
  '拉萨',
  '香港',
  '澳门',
  '台北',
  '东京',
  '大阪',
  '京都',
  '首尔',
  '曼谷',
  '清迈',
  '新加坡',
  '巴黎',
  '伦敦',
  '罗马',
  '纽约',
];

const INTEREST_KEYWORDS: { pattern: RegExp; value: (typeof INTEREST_OPTIONS)[number] }[] = [
  { pattern: /美食|小吃|好吃的|吃[的地]|餐厅/, value: '当地美食' },
  { pattern: /拍照|打卡|出片/, value: '拍照打卡' },
  { pattern: /购物|买买买|商场|免税/, value: '购物' },
  { pattern: /历史|人文|博物馆|古迹|文化/, value: '历史文化' },
  { pattern: /自然|风景|徒步|爬山|海边|湖/, value: '自然风景' },
  { pattern: /亲子|孩子|小朋友|儿童/, value: '亲子体验' },
  { pattern: /地标|知名景点|必去/, value: '经典地标' },
  { pattern: /小众|街区|city ?walk/i, value: '小众街区' },
];

/** 去掉「帮我规划」「周末」「去」这类前缀词，得到干净的城市名 */
function cleanCity(raw: string): string | null {
  let s = raw.replace(/[\s，,。;；、]/g, '');
  // 「3大1小去成都玩」这类：「1小」属于人数，不是城市名的一部分
  s = s.replace(/^[大小](?=去|到|在)/, '');
  s = s.replace(/^[\d零一二两三四五六七八九十]+[大小]?/, '');
  s = s.replace(/^(帮我|帮忙|给我|我想|想|计划|规划|安排|准备|预订)+/g, '');
  s = s.replace(
    /^(周末|假期|小长假|春节|国庆|暑假|寒假|这周|下周|本周|今年|明年|下个月|这个月|月初|月底)+/g,
    '',
  );
  s = s.replace(/^(去|到|在|玩|游|旅行|旅游|出差|度假|待|呆|逛)+/g, '');
  // 城市名后面的动词是句式残留，不属于城市名
  s = s.replace(/(玩|游|旅行|旅游|出差|度假|逛|吃喝|住)+$/g, '');
  s = s.replace(/的$/, '');
  if (s.length < 2 || s.length > 6) return null;
  if (CITY_STOPWORDS.has(s)) return null;
  return s;
}

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

function currentYear(): number {
  return new Date().getFullYear();
}

function iso(y: number, m: number, d: number): string | null {
  if (m < 1 || m > 12 || d < 1 || d > 31) return null;
  return `${y}-${pad(m)}-${pad(d)}`;
}

function spanDays(start: string, end: string): number {
  const a = Date.parse(`${start}T00:00:00`);
  const b = Date.parse(`${end}T00:00:00`);
  if (Number.isNaN(a) || Number.isNaN(b)) return 0;
  return Math.round((b - a) / 86400000) + 1;
}

/**
 * 把自然语言解析成结构化需求。找不到字段时返回全 null / matched=false，
 * 调用方应把原话原样保留，不做任何猜测性回填。
 */
export function parsePlanPrompt(text: string): ParsedPlanPrompt {
  const source = (text ?? '').trim();
  const confidence: ParsedPlanPrompt['confidence'] = {
    destination: 'missing',
    days: 'missing',
    people: 'missing',
    budget: 'missing',
    dates: 'missing',
  };
  const result: ParsedPlanPrompt = {
    destination: null,
    days: null,
    startDate: null,
    endDate: null,
    adults: 1,
    kids: 0,
    budget: null,
    interests: [],
    confidence,
    conflicts: [],
    leftovers: source,
    matched: false,
  };
  if (!source) return result;

  /** 命中后从剩余文本里抹掉，避免同一段被多个字段重复吸收 */
  let rest = source;
  const consume = (matched: string | undefined) => {
    if (matched) rest = rest.replace(matched, ' ');
  };

  // ---------- 1) 人数：2人 / 两个人 / 3大1小 / 独自 ----------
  const adultKid = rest.match(
    /(\d+|[一二两三四五六七八九十])\s*大\s*(\d+|[一二两三四五六七八九十])\s*小/,
  );
  if (adultKid) {
    const adults = toNumber(adultKid[1]);
    const kids = toNumber(adultKid[2]);
    if (adults) result.adults = adults;
    if (kids !== null) result.kids = kids;
    confidence.people = 'high';
    consume(adultKid[0]);
  } else {
    const people = rest.match(/(\d+|[一二两三四五六七八九十]{1,3})\s*(?:个?人|位)/);
    if (people) {
      const n = toNumber(people[1]);
      if (n && n > 0 && n <= 30) {
        result.adults = n;
        confidence.people = 'high';
        consume(people[0]);
      }
    } else if (/独自|一个人|我自己|单人/.test(rest)) {
      result.adults = 1;
      confidence.people = 'high';
      consume(rest.match(/独自|一个人|我自己|单人/)?.[0]);
    }
    if (confidence.people === 'missing' && /亲子/.test(rest)) {
      // 只说了「亲子」没说几个人：记 1 个孩子待确认，不编造大人数量
      result.kids = 1;
      confidence.people = 'low';
    }
  }

  // ---------- 2) 目的地：城市 + 天数是最强句式（上海三日游 / 北京两日游） ----------
  // 注意：人数先抽走（见下），否则「3大1小去成都玩」里的「小」会被城市名句式吃掉
  const cityWithDays = rest.match(new RegExp(`([\\u4e00-\\u9fa5]{2,8}?)\\s*${DAY_TOKEN}`));
  if (cityWithDays) {
    const candidate = cleanCity(cityWithDays[1]);
    if (candidate) {
      result.destination = candidate;
      confidence.destination = 'high';
      // 只抹掉城市那段，把「三日游」留给天数解析
      consume(cityWithDays[1]);
    }
  }
  if (!result.destination) {
    const verbMatch = rest.match(
      /(?:去|到|在|想去|计划去)\s*([\u4e00-\u9fa5]{2,6}?)(?:玩|旅游|旅行|出差|度假|逛|待|呆|，|,|。|\s|$)/,
    );
    const candidate = verbMatch?.[1] ? cleanCity(verbMatch[1]) : null;
    if (candidate) {
      result.destination = candidate;
      confidence.destination = 'high';
      consume(verbMatch?.[0]);
    }
  }
  if (!result.destination) {
    const known = KNOWN_CITIES.find((city) => rest.includes(city));
    if (known) {
      result.destination = known;
      confidence.destination = 'low';
      consume(known);
    }
  }

  // ---------- 3) 天数：三天 / 3天 / 两日游 ----------
  const dayMatch = rest.match(/(\d+|[零一二两三四五六七八九十]{1,3})\s*(?:天|日游)/);
  if (dayMatch) {
    const days = toNumber(dayMatch[1]);
    if (days && days > 0 && days <= 30) {
      result.days = days;
      confidence.days = 'high';
      consume(dayMatch[0]);
    }
  }
  if (!result.days && /周末/.test(rest) && !result.startDate) {
    // 「周末」按两天粗估，必须让用户确认
    result.days = 2;
    confidence.days = 'low';
    consume('周末');
  }

  // ---------- 4) 预算：预算3000元 / 人均 800 / 预算1.5万 ----------
  // 人均价单独走一支：总额要乘人数，而人数可能只是猜的 → 低置信
  const perPersonMatch = rest.match(/人均\s*(\d+(?:\.\d+)?)\s*(元|块钱|块|w|万)?/i);
  if (perPersonMatch) {
    let value = Number(perPersonMatch[1]);
    const unit = (perPersonMatch[2] || '').toLowerCase();
    if (unit === '万' || unit === 'w') value *= 10000;
    if (value > 0) {
      result.budget = Math.round(value * (result.adults || 1));
      confidence.budget = 'low';
      consume(perPersonMatch[0]);
    }
  }
  const budgetMatch = rest.match(
    /(?:预算|大概|大约|不超过|控制在|花)\s*(\d+(?:\.\d+)?)\s*(元|块钱|块|w|万)?/i,
  );
  if (!perPersonMatch && budgetMatch) {
    let value = Number(budgetMatch[1]);
    const unit = (budgetMatch[2] || '').toLowerCase();
    if (unit === '万' || unit === 'w') value *= 10000;
    if (value > 0) {
      result.budget = Math.round(value);
      confidence.budget = 'high';
      consume(budgetMatch[0]);
    }
  }

  // ---------- 5) 日期范围：11月21日-11月23日 / 2026-11-21 至 2026-11-24 ----------
  const isoRange = rest.match(
    /(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s*(?:-|~|到|至|—)\s*(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/,
  );
  if (isoRange) {
    const start = iso(Number(isoRange[1]), Number(isoRange[2]), Number(isoRange[3]));
    const end = iso(Number(isoRange[4]), Number(isoRange[5]), Number(isoRange[6]));
    if (start && end) {
      result.startDate = start;
      result.endDate = end;
      confidence.dates = 'high';
      consume(isoRange[0]);
    }
  }
  if (!result.startDate) {
    const cnRange = rest.match(
      /(\d{4}年)?(\d{1,2})月(\d{1,2})[日号]?\s*(?:-|~|到|至|—)\s*(?:(\d{4})年)?(\d{1,2})月(\d{1,2})[日号]?/,
    );
    if (cnRange) {
      const year = Number(
        cnRange[1]?.replace('年', '') || cnRange[4]?.replace('年', '') || currentYear(),
      );
      const start = iso(year, Number(cnRange[2]), Number(cnRange[3]));
      const end = iso(year, Number(cnRange[5]), Number(cnRange[6]));
      if (start && end) {
        result.startDate = start;
        result.endDate = end;
        confidence.dates = 'high';
        consume(cnRange[0]);
      }
    }
  }

  // ---------- 6) 兴趣偏好 ----------
  for (const { pattern, value } of INTEREST_KEYWORDS) {
    const hit = rest.match(pattern);
    if (!hit) continue;
    if (!result.interests.includes(value)) result.interests.push(value);
    consume(hit[0]);
  }

  // ---------- 7) 一致性检查：天数 vs 日期跨度 ----------
  if (result.days && result.startDate && result.endDate) {
    const span = spanDays(result.startDate, result.endDate);
    if (span > 0 && span !== result.days) {
      result.conflicts.push(
        `「${result.days} 天」和日期范围（${result.startDate} 至 ${result.endDate}，共 ${span} 天）对不上，请确认以哪个为准`,
      );
    }
  }

  // ---------- 8) 剩余文本 → 特别要求 ----------
  const leftover = rest
    .replace(/[，,。；;、]+/g, ' ')
    .replace(
      /(帮我|帮忙|安排一下|安排|规划一下|规划|一下|吧|呢|谢谢|喜欢|想要|想|还有|以及|和|与)/g,
      ' ',
    )
    .replace(/\s+/g, ' ')
    .trim();
  result.leftovers = leftover.length >= 2 ? leftover : '';

  result.matched =
    result.destination !== null ||
    result.days !== null ||
    result.startDate !== null ||
    result.budget !== null ||
    confidence.people !== 'missing';
  return result;
}

/**
 * 把解析结果落到表单上：只覆盖解析到的字段，其余保持默认值。
 * `notes` 只接收未解析的残余要求；完全解析不出来时保留原话。
 */
export function applyParsedToForm(
  base: PlanFormState,
  parsed: ParsedPlanPrompt,
  rawPrompt: string,
): PlanFormState {
  if (!parsed.matched) {
    return { ...base, mode: 'scratch', notes: rawPrompt.trim() };
  }
  return {
    ...base,
    mode: 'scratch',
    destination: parsed.destination ?? base.destination,
    days: parsed.days ?? base.days,
    startDate: parsed.startDate ?? base.startDate,
    endDate: parsed.endDate ?? base.endDate,
    adults: parsed.adults,
    kids: parsed.kids,
    budgetEnabled: parsed.budget !== null ? true : base.budgetEnabled,
    budget: parsed.budget ?? base.budget,
    interests: parsed.interests.length > 0 ? parsed.interests : base.interests,
    notes: parsed.leftovers,
  };
}

/** 界面用的一行摘要，如「上海 · 3 天 · 2 人 · 预算 ¥3000」 */
export function describeParsed(parsed: ParsedPlanPrompt): string[] {
  const parts: string[] = [];
  if (parsed.destination) parts.push(parsed.destination);
  if (parsed.startDate && parsed.endDate) parts.push(`${parsed.startDate} 至 ${parsed.endDate}`);
  if (parsed.days) parts.push(`${parsed.days} 天`);
  if (parsed.adults + parsed.kids > 0) {
    parts.push(parsed.kids > 0 ? `${parsed.adults} 大 ${parsed.kids} 小` : `${parsed.adults} 人`);
  }
  if (parsed.budget !== null) parts.push(`预算 ¥${parsed.budget}`);
  if (parsed.interests.length > 0) parts.push(parsed.interests.join('、'));
  return parts;
}

/** 需要用户确认的字段（低置信）文案 */
export function lowConfidenceHints(parsed: ParsedPlanPrompt): string[] {
  const hints: string[] = [];
  if (parsed.confidence.destination === 'low') hints.push('目的地是按城市名猜的，请确认');
  if (parsed.confidence.days === 'low') hints.push('天数按「周末」粗估为 2 天，请确认');
  if (parsed.confidence.people === 'low') hints.push('同行人数不明确，请确认');
  if (parsed.confidence.budget === 'low') hints.push('预算是按人均折算的，请确认总额');
  return hints;
}
