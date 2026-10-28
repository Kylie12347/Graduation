/** 本地商品示意图随小程序打包，无需外网图片域名。新SKU使用通用包装图。 */
const pictures = { COLA: 'cola', WATER: 'water', TEA: 'tea', JUICE: 'juice', BISCUIT: 'biscuit' };
const imageFor = id => '/assets/products/' + (pictures[id] || 'generic') + '.png';
/** 二维码只携带柜体身份，不接受二维码指定金额或商品清单。支持普通码和小程序码scene。 */
function parseCabinetId(raw) {
  if (typeof raw !== 'string') return '';
  let value = raw.trim();
  for (let i = 0; i < 2; i++) { try { const next = decodeURIComponent(value); if (next === value) break; value = next; } catch (_) { return ''; } }
  const named = value.match(/(?:^|[?&])cabinetId=([A-Za-z0-9_-]{1,40})(?=&|$)/);
  if (named) return named[1];
  const scene = value.match(/(?:^|[?&])scene=([^&]+)/);
  if (scene) return parseCabinetId(scene[1]);
  return /^[A-Za-z0-9_-]{1,40}$/.test(value) ? value : '';
}
module.exports = { imageFor, parseCabinetId };
