const { parseCabinetId } = require('./utils/storefront');
/** App管理扫码入口；柜号只能从有效二维码/启动参数绑定，不提供手动选柜开门。 */
App({
  globalData: { baseUrl: 'http://127.0.0.1:8080/api', pendingCabinetId: '', boundCabinetId: '', previewCabinetId: '' },
  onLaunch(options) {
    this.globalData.token = wx.getStorageSync('consumerToken') || '';
    this.captureCabinet(options);
  },
  onShow(options) { this.captureCabinet(options); },
  captureCabinet(options) {
    const query = options && options.query || {};
    const id = parseCabinetId(query.cabinetId || query.scene || '');
    if (id) this.globalData.pendingCabinetId = id;
  }
});
