export interface Show {
  id: number
  name: string
  description?: string
  images: string              // JSON数组格式：["url1", "url2"]
  openTime: string            // 开演时间
  endTime: string             // 结束时间
  address: string             // 详细地址
  status: number              // 状态：0=下架, 1=上架, 2=已结束
  typeId: number              // 演出类型ID
  area?: string               // 区域名称
  avgPrice: number            // 平均票价（单位：分）
  sold: number                // 已售数量
  comments?: number           // 评论数量
  score?: number              // 评分
  createTime?: string         // 创建时间
  updateTime?: string         // 更新时间
  distance?: number           // LBS距离（米）
  tickets?: Ticket[]
}

import { Ticket } from './ticket'
