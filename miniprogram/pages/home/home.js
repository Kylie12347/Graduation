const { request, money, message, states } = require('../../utils/api');
const { imageFor, parseCabinetId } = require('../../utils/storefront');

Page({
  data: {
    cabinets: [],
    cabinet: null,
    products: [],
    active: null,
    bound: false,
    loading: false,
    opening: false,
    error: ''
  },

  onLoad(options = {}) {
    const id = parseCabinetId(
      options.cabinetId || options.scene || ''
    );

    if (id) {
      getApp().globalData.pendingCabinetId = id;
    }
  },

  onShow() {
    this.visible = true;
    this.navigating = false;
    // 首页是常驻Tab，先清除上次视图，再以服务端重新查询的会话为准。
    this.setData({ active: null });
    const app = getApp().globalData;
    if (app.checkoutNotice) {
      wx.showToast({ title: app.checkoutNotice, icon: 'success', duration: 2000 });
      app.checkoutNotice = '';
    }
    this.refresh();
    clearInterval(this.timer);
    this.timer = setInterval(() => this.refresh(), 2500);
  },

  onHide() {
    this.visible = false;
    clearInterval(this.timer);
  },

  onUnload() {
    this.visible = false;
    clearInterval(this.timer);
  },

  async onPullDownRefresh() {
    try {
      await this.refresh();
    } finally {
      wx.stopPullDownRefresh();
    }
  },

  async refresh() {
    if (this.refreshing) {
      this.refreshAgain = true;
      return;
    }

    this.refreshing = true;
    this.setData({ loading: true });

    const app = getApp().globalData;
    const incoming = app.pendingCabinetId;

    try {
      const [cabinets, sessions] = await Promise.all([
        request('/cabinets'),
        request('/sessions')
      ]);

      let active = sessions.find(
        s => ['OPEN', 'REVIEW', 'UNPAID'].includes(s.state) && !(app.completedSessionIds || []).includes(s.id)
      );

      const id =
        incoming ||
        (active && active.cabinet_id) ||
        app.previewCabinetId ||
        app.boundCabinetId ||
        (cabinets[0] && cabinets[0].id);

      let catalog = null;

      if (id) {
        catalog = await request(
          '/cabinets/' + encodeURIComponent(id) + '/catalog'
        );
      }

      // catalog请求期间可能刚完成支付，再检查一次终态记录，阻止旧请求触发跳转。
      if (active && (app.completedSessionIds || []).includes(active.id)) active = null;

      // 只有后端确认柜体存在后，才接受二维码中的柜号。
      if (incoming && catalog) {
        app.boundCabinetId = catalog.cabinet.id;

        if (app.pendingCabinetId === incoming) {
          app.pendingCabinetId = '';
        }
      }

      app.previewCabinetId = '';

      if (this.visible) {
        this.setData({
          cabinets,
          active: active
            ? { ...active, label: states[active.state] }
            : null,
          cabinet: catalog ? catalog.cabinet : null,
          products: catalog
            ? catalog.products.map(p => ({
                ...p,
                price: money(p.price_cents),
                image: imageFor(p.id)
              }))
            : [],
          bound:
            !!catalog &&
            app.boundCabinetId === catalog.cabinet.id,
          error: ''
        });
        // 即使消费者停在首页，也能发现电脑仿真面板已经关柜。
        if (active && ['REVIEW', 'UNPAID'].includes(active.state) && !this.navigating) {
          this.navigating = true;
          wx.navigateTo({
            url: '/pages/shopping/shopping?id=' + encodeURIComponent(active.id),
            fail: () => { this.navigating = false; }
          });
        }
      }
    } catch (e) {
      console.error('首页加载失败：', e);

      if (this.visible) {
        this.setData({
          error: e.message,
          bound: false
        });
      }
    } finally {
      this.refreshing = false;

      if (this.visible) {
        this.setData({ loading: false });
      }

      if (this.refreshAgain) {
        this.refreshAgain = false;

        if (this.visible) {
          this.refresh();
        }
      }
    }
  },

  /**
   * 判断是否运行在微信开发者工具中。
   * 只有开发者工具允许提供模拟扫码入口。
   */
  isDevtools() {
    try {
      let info = null;

      if (typeof wx.getDeviceInfo === 'function') {
        info = wx.getDeviceInfo();
      }

      if (!info || !info.platform) {
        info = wx.getSystemInfoSync();
      }

      return !!info && info.platform === 'devtools';
    } catch (e) {
      console.warn('无法判断运行环境：', e);
      return false;
    }
  },

  /**
   * 正常扫码入口。
   * 从二维码提取唯一柜号，不能把当前预览柜当作扫码结果。
   */
  scan() {
    if (this.data.opening) return;
    if (this.data.active) return this.resume();

    this.setData({ opening: true });

    try {
      wx.scanCode({
        onlyFromCamera: false,
        scanType: ['qrCode'],

        success: async res => {
          console.log('扫码成功：', res);

          try {
            // 小程序码可能返回path，普通二维码通常返回result。
            const id =
              parseCabinetId(res.path || '') ||
              parseCabinetId(res.result || '');

            if (!id) {
              throw new Error(
                '二维码内容不是有效柜号，请使用 CAB001 或包含 cabinetId 的链接。'
              );
            }

            await this.connectCabinet(id);
          } catch (e) {
            console.error('扫码后的开柜处理失败：', e);
            message(e);
          } finally {
            this.setData({ opening: false });
          }
        },

        fail: async err => {
          try {
            await this.handleScanFailure(err);
          } catch (e) {
            console.error('扫码失败处理异常：', e);
            message(e);
          } finally {
            this.setData({ opening: false });
          }
        }
      });
    } catch (e) {
      this.setData({ opening: false });
      console.error('扫码接口调用异常：', e);
      message(e);
    }
  },

  /**
   * 保留原始错误，避免所有异常都变成一句“扫码失败”。
   * 开发工具异常时，可明确选择模拟进入当前展示柜体。
   */
  async handleScanFailure(err) {
    const detail =
      err && err.errMsg ? err.errMsg : '未知扫码错误';

    console.error('扫码具体错误：', err);

    // 用户主动取消，不弹出错误或模拟入口。
    if (/cancel/i.test(detail)) return;

    const cabinet = this.data.cabinet;

    if (!this.isDevtools() || !cabinet) {
      message(new Error('未能识别二维码。\n' + detail));
      return;
    }

    const confirmed = await new Promise((resolve, reject) => {
      wx.showModal({
        title: '开发工具扫码异常',
        content:
          '扫码接口未能读取二维码。\n\n' +
          '是否模拟进入当前展示的“' +
          cabinet.name +
          '”（' +
          cabinet.id +
          '）？\n\n' +
          '这仅用于联调，不代表二维码识别成功。',
        confirmText: '模拟开柜',
        cancelText: '取消',
        success: res => resolve(res.confirm),
        fail: reject
      });
    });

    if (confirmed) {
      await this.connectCabinet(cabinet.id);
    }
  },

  /**
   * 正常扫码和开发模拟共用业务校验：
   * 验证柜体存在 → 检查可用状态 → 请求后端开柜。
   */
  async connectCabinet(id) {
    const catalog = await request(
      '/cabinets/' + encodeURIComponent(id) + '/catalog'
    );

    if (!catalog.cabinet.available) {
      throw new Error('这台售货柜正在使用中，请稍后再试。');
    }

    const app = getApp().globalData;

    app.boundCabinetId = catalog.cabinet.id;
    app.pendingCabinetId = '';
    app.previewCabinetId = '';

    this.setData({
      cabinet: catalog.cabinet,
      bound: true
    });

    await this.openCabinet(catalog.cabinet.id);
  },

  /** 从柜体二维码启动后，点击开启已绑定的唯一柜体。 */
  async openBound() {
    if (this.data.opening) return;
    if (this.data.active) return this.resume();

    const id = getApp().globalData.boundCabinetId;

    if (!this.data.bound || !id) {
      message(new Error('请先扫描柜体二维码。'));
      return;
    }

    this.setData({ opening: true });

    try {
      await this.connectCabinet(id);
    } catch (e) {
      console.error('开柜失败：', e);
      message(e);
    } finally {
      this.setData({ opening: false });
    }
  },

  async openCabinet(id) {
    const session = await request('/sessions', 'POST', {
      cabinetId: id
    });

    // 一次扫码绑定只用于本次开柜。支付结束后必须重新扫码。
    const app = getApp().globalData;
    app.boundCabinetId = '';
    app.pendingCabinetId = '';
    app.previewCabinetId = '';
    // 即使页面跳转失败，也保留已创建的会话，避免再次申请开柜。
    this.setData({
      bound: false,
      active: {
        ...session,
        label: states[session.state]
      }
    });

    wx.navigateTo({
      url:
        '/pages/shopping/shopping?id=' +
        encodeURIComponent(session.id),
      fail: err => {
        console.error('购物页跳转失败：', err);
        message(
          new Error('柜门已打开，请点击首页“继续购物 / 结算”。')
        );
      }
    });
  },

  resume() {
    if (!this.data.active) return;

    wx.navigateTo({
      url:
        '/pages/shopping/shopping?id=' +
        encodeURIComponent(this.data.active.id)
    });
  },

  nearby() {
    wx.navigateTo({
      url: '/pages/nearby/nearby'
    });
  },

  imageError(e) {
    const index = e.currentTarget.dataset.index;

    this.setData({
      ['products[' + index + '].image']: imageFor('')
    });
  },

  refreshTap() {
    this.refresh();
  }
});