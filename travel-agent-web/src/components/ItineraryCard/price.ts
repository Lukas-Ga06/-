import type { ItineraryPrice, ItineraryPriceLink } from '../../types/trip';

/**
 * 价格溯源展示的纯函数层：金额格式化、深链过滤、禁词兜底。
 *
 * 红线（产品/合规定死）：展示层**不得**出现「实时 / 可订 / 有房 / 立即预订」这类承诺性字样，
 * 统一表述为「参考价」。后端给的 label / sourceLabel / 链接文案都不可信，
 * 一律先过 `sanitizePriceCopy`，命中禁词就退回中性文案。
 */
export const PRICE_TAG_TEXT = '参考价';

/** 禁止出现在 UI 文案里的承诺性字样（后端 disclaimer 原文除外，见 ItineraryCard） */
export const FORBIDDEN_PRICE_CLAIMS = ['实时', '可订', '有房', '立即预订'] as const;

/** kind → 固定外链文案；后端 label 只在未知 kind 时作为兜底候选 */
const LINK_LABEL_BY_KIND: Record<string, string> = {
  price: '去携程查价',
  map: '在地图查看',
};

/** kind 的渲染顺序：查价在前、地图在后，未知 kind 排最后（保持后端给出的相对顺序） */
const LINK_KIND_ORDER: Record<string, number> = { price: 0, map: 1 };

const UNKNOWN_LINK_LABEL = '查看详情';
const UNKNOWN_SOURCE_LABEL = '来源未标注';

/** 文案里是否含承诺性字样 */
export function hasForbiddenPriceClaim(text: string | null | undefined): boolean {
  if (!text) return false;
  return FORBIDDEN_PRICE_CLAIMS.some((word) => text.includes(word));
}

/** 兜底文案：原文案为空或命中禁词时返回 fallback */
export function sanitizePriceCopy(
  text: string | null | undefined,
  fallback: string,
): string {
  const raw = text?.trim();
  return raw && !hasForbiddenPriceClaim(raw) ? raw : fallback;
}

/** 金额旁的「参考价」标签：后端 label 安全才用，否则固定「参考价」 */
export function priceTagText(price: ItineraryPrice | null | undefined): string {
  return sanitizePriceCopy(price?.label, PRICE_TAG_TEXT);
}

/** 价格来源（如「本地估算」）：缺省时返回空串，表示不渲染这一行 */
export function priceSourceText(price: ItineraryPrice | null | undefined): string {
  const raw = price?.sourceLabel?.trim();
  if (!raw) return '';
  return sanitizePriceCopy(raw, UNKNOWN_SOURCE_LABEL);
}

/** 只放行 http(s) 外链，避免把 javascript: 之类的地址渲染成可点链接 */
export function safePriceUrl(url: string | null | undefined): string | null {
  const raw = url?.trim();
  if (!raw) return null;
  return /^https?:\/\//i.test(raw) ? raw : null;
}

/** 外链文案：已知 kind 用固定文案（不采信后端 label），未知 kind 才用安全化的后端 label */
export function priceLinkText(link: ItineraryPriceLink | null | undefined): string {
  const fixed = LINK_LABEL_BY_KIND[String(link?.kind ?? '')];
  if (fixed) return fixed;
  return sanitizePriceCopy(link?.label, UNKNOWN_LINK_LABEL);
}

/** 可渲染的外链：url 必须是 http(s)；按 kind 排序（price → map → 其它），同 kind 保持后端顺序 */
export function visiblePriceLinks(
  price: ItineraryPrice | null | undefined,
): ItineraryPriceLink[] {
  const links = (price?.links ?? []).filter(
    (link): link is ItineraryPriceLink => !!link && safePriceUrl(link.url) != null,
  );
  return links
    .map((link, index) => ({ link, index }))
    .sort((a, b) => {
      const ka = LINK_KIND_ORDER[String(a.link.kind ?? '')] ?? 99;
      const kb = LINK_KIND_ORDER[String(b.link.kind ?? '')] ?? 99;
      return ka === kb ? a.index - b.index : ka - kb;
    })
    .map((entry) => entry.link);
}

/**
 * 参考金额格式化：`amount=520`、currency=CNY → `¥520`；
 * 非 CNY 时用 `USD 520` 这种带币种码的写法，避免把美元显示成人民币。
 */
export function formatPriceAmount(
  amount: number,
  currency: string | null | undefined,
): string {
  const rounded = Math.round(amount * 100) / 100;
  const value = Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(2);
  const code = (currency ?? 'CNY').trim().toUpperCase();
  return code === 'CNY' || code === '' ? `¥${value}` : `${code} ${value}`;
}

/** price.amount 是否是可展示的参考金额（旧后端/后端给 null 都走回退） */
export function hasReferenceAmount(
  price: ItineraryPrice | null | undefined,
): price is ItineraryPrice & { amount: number } {
  return typeof price?.amount === 'number' && Number.isFinite(price.amount);
}