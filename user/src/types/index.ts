export * from './ticket'
export * from './show'
export * from './order'

export interface ApiResponse<T = any> {
  code: number
  message: string
  data: T
}

export interface PaginatedData<T = any> {
  records: T[]
  total: number
  current: number
  size: number
  pages: number
}
