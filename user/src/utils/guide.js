export function guideHome() {
  return import.meta.env.VITE_GUIDE_URL || 'http://127.0.0.1:8082'
}

export function newGuideRequestId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  const bytes = new Uint8Array(16)
  globalThis.crypto.getRandomValues(bytes)
  return Array.from(bytes, x => x.toString(16).padStart(2, '0')).join('')
}

export function isSeckill(ticket) { return ticket?.type === 1 || ticket?.isSeckill === true || ticket?.seckillTicket === true }
export function ticketStock(ticket) {
  const raw = isSeckill(ticket) ? ticket.seckillStock : ticket.remainingStock ?? ticket.stock
  const amount = Number(raw)
  return Number.isFinite(amount) ? Math.max(0, amount) : 0
}
export function echoTimeMillis(value) {
  if(value==null||value==='')return NaN
  if(typeof value==='number')return value<1e12?value*1000:value
  const text=String(value).replace(' ','T')
  if(/^\d+$/.test(text))return echoTimeMillis(Number(text))
  return new Date(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?$/.test(text)?text+'+08:00':text).getTime()
}
export function priceYuan(cents) { return (Number(cents) / 100).toFixed(2) }
