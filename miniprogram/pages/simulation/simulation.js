const { request, message } = require('../../utils/api');
const { imageFor } = require('../../utils/storefront');
const endpoint = id => '/sessions/' + encodeURIComponent(id);
const key = id => 'simulation-' + id;
const limit = 6;

Page({
  data: { active: null, busy: false, before: '', after: '', errorGrams: '0', rows: [], shown: [], photos: [], filter: '', showAll: false, error: '' },
  onShow() { this.load(); },
  async load() {
    if (this.data.busy) return;
    this.setData({ busy: true, error: '' });
    try {
      const sessions = await request('/sessions');
      const active = sessions.find(s => ['OPEN', 'REVIEW', 'UNPAID'].includes(s.state)) || null;
      this.baseline = null;
      this.setData({ active, rows: [], shown: [], photos: [] });
      if (!active || active.state !== 'OPEN') return;
      const [snapshot, detail] = await Promise.all([request(endpoint(active.id) + '/simulation'), request(endpoint(active.id))]);
      const saved = wx.getStorageSync(key(active.id)) || {};
      this.baseline = snapshot;
      this.frames = saved.frames || [];
      const goods = new Map(snapshot.catalog.map(p => [p.id, p]));
      const read = new Set(saved.beforeTags || snapshot.tags);
      const after = new Set(saved.afterTags || snapshot.tags);
      const rows = snapshot.tags.map(tag => {
        const product = goods.get(snapshot.tagProducts[tag]);
        return { tag, name: product ? product.name : snapshot.tagProducts[tag], before: read.has(tag), after: after.has(tag) };
      });
      this.setData({ active: { ...active, cabinetName: detail.cabinetName }, before: saved.before ?? String(snapshot.grams), after: saved.after ?? String(snapshot.grams), errorGrams: saved.errorGrams ?? '0', filter: '', showAll: false, rows });
      this.renderRows(); this.renderPhotos();
    } catch (e) { this.setData({ error: e.message }); }
    finally { this.setData({ busy: false }); }
  },
  save() {
    if (!this.data.active || !this.baseline) return;
    const { before, after, errorGrams, rows, active } = this.data;
    try { wx.setStorageSync(key(active.id), { before, after, errorGrams, beforeTags: rows.filter(r => r.before).map(r => r.tag), afterTags: rows.filter(r => r.after).map(r => r.tag), frames: this.frames }); }
    catch (_) { /* 仍可从本页提交当前内存草稿。 */ }
  },
  input(e) { this.setData({ [e.currentTarget.dataset.field]: e.detail.value }); this.save(); },
  filter(e) { this.setData({ filter: e.detail.value }); this.renderRows(); },
  showMore() { this.setData({ showAll: !this.data.showAll }); this.renderRows(); },
  renderRows() {
    const filter = this.data.filter.trim().toLowerCase();
    const matching = this.data.rows.filter(r => !filter || (r.name + ' ' + r.tag).toLowerCase().includes(filter));
    this.setData({ shown: this.data.showAll ? matching : matching.slice(0, 20), matching: matching.length });
  },
  toggleTag(e) {
    const { tag, field } = e.currentTarget.dataset;
    if (this.data.busy || !['before', 'after'].includes(field)) return;
    this.setData({ rows: this.data.rows.map(row => row.tag === tag ? { ...row, [field]: !row[field] } : row) });
    this.renderRows(); this.save();
  },
  renderPhotos() {
    this.setData({ photos: this.frames.map(({ path, timestamp }) => ({ path, timestamp, label: new Date(timestamp).toLocaleString() })) });
  },
  async addPhotos() {
    if (this.data.busy || !this.frames || this.frames.length >= limit) return;
    this.setData({ busy: true });
    try {
      const result = await new Promise((resolve, reject) => wx.chooseMedia({ count: limit - this.frames.length, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['compressed'], success: resolve, fail: reject }));
      for (const file of result.tempFiles) {
        let path = file.tempFilePath;
        if (file.size > 330000) {
          const compressed = await new Promise((resolve, reject) => wx.compressImage({ src: path, quality: 45, success: resolve, fail: reject }));
          path = compressed.tempFilePath;
        }
        const content = await new Promise((resolve, reject) => wx.getFileSystemManager().readFile({ filePath: path, encoding: 'base64', success: r => resolve(r.data), fail: reject }));
        const type = content.startsWith('/9j/') ? 'jpeg' : content.startsWith('iVBORw0KGgo') ? 'png' : '';
        if (!type || content.length > 449950) throw new Error('请使用 330 KB 以下的 JPEG 或 PNG 照片。');
        const timestamp = Math.max(Date.now(), (this.frames[this.frames.length - 1]?.timestamp || 0) + 1);
        const dataUrl = 'data:image/' + type + ';base64,' + content;
        this.frames.push({ path, timestamp, dataUrl });
      }
    } catch (e) { if (!/cancel/i.test(e.errMsg || '')) message(e); }
    finally { this.renderPhotos(); this.save(); this.setData({ busy: false }); }
  },
  removePhoto(e) {
    if (this.data.busy) return;
    this.frames = this.frames.filter(f => f.timestamp !== Number(e.currentTarget.dataset.timestamp));
    this.renderPhotos(); this.save();
  },
  movePhoto(e) {
    if (this.data.busy) return;
    const i = Number(e.currentTarget.dataset.index);
    if (i < 1 || i >= this.frames.length) return;
    const a = this.frames[i - 1], b = this.frames[i];
    // 调整的是回放顺序，不使用相册未提供的原始拍摄时间。
    this.frames[i - 1] = { ...b, timestamp: a.timestamp };
    this.frames[i] = { ...a, timestamp: b.timestamp };
    this.renderPhotos(); this.save();
  },
  async submit() {
    if (this.data.busy || this.data.active?.state !== 'OPEN' || !this.baseline) return;
    const { active, before, after, errorGrams, rows } = this.data;
    const a = Number(before), b = Number(after), error = Number(errorGrams || 0);
    if (!before.trim() || !after.trim() || ![a, b, error].every(Number.isSafeInteger) || a < 0 || b < 0 || b + error < 0 || Math.abs(error) > 500) return message(new Error('请填写有效的整数重量，模拟误差范围为 −500 至 500 克。'));
    if (this.frames.length === 1) return message(new Error('请至少提供两张按顺序排列的照片，或移除这张照片后交运营人员核对。'));
    this.setData({ busy: true });
    try {
      const closed = await request(endpoint(active.id) + '/perception', 'POST', {
        beforeGrams: a, afterGrams: b + error,
        beforeTags: rows.filter(r => r.before).map(r => r.tag),
        afterTags: rows.filter(r => r.after).map(r => r.tag),
        photos: this.frames.map(({ timestamp, dataUrl }) => ({ timestamp, dataUrl }))
      });
      wx.removeStorageSync(key(active.id));
      this.setData({ active: { ...closed, cabinetName: closed.cabinetName } });
      this.shopping();
    } catch (e) { message(e); }
    finally { this.setData({ busy: false }); }
  },
  async abandon() {
    if (this.data.busy || this.data.active?.state !== 'OPEN') return;
    wx.showModal({ title: '结束本次演示', content: '仅在没有净取出商品时使用。是否放弃本次草稿并锁柜？', success: async result => {
      if (!result.confirm || this.data.busy) return;
      this.setData({ busy: true });
      const id = this.data.active.id;
      try {
        await request(endpoint(id) + '/abandon', 'POST');
        wx.removeStorageSync(key(id));
        this.setData({ active: null });
      } catch (e) { message(e); }
      finally { this.setData({ busy: false }); }
    } });
  },
  shopping() {
    if (this.data.active) wx.navigateTo({ url: '/pages/shopping/shopping?id=' + encodeURIComponent(this.data.active.id) });
  },
  home() { wx.switchTab({ url: '/pages/home/home' }); }
});
