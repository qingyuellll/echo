/*
 * 地图服务配置文件
 *
 * API Key信息：
 * - 应用名称：echo
 * - 创建时间：2026/8/20
 * - 绑定服务：Web服务
 * - 免费额度：每日5000次调用
 *
 * 申请地址：https://console.amap.com/dev/key/app
 *
 * 注意事项：
 * - 已配置您的API Key，可直接使用
 * - 生产环境建议使用环境变量存储Key（见 .env.example）
 * - 请勿将此文件提交到公开Git仓库
 */

const MAP_CONFIG = {
  amap: {
    key: import.meta.env.VITE_AMAP_KEY || '',
    webApiKey: ''
  },
  baidu: {
    ak: import.meta.env.VITE_BAIDU_MAP_AK || ''
  }
}

export default MAP_CONFIG
