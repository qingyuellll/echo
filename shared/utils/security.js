/**
 * Security Utility Library
 * Prevent XSS, SQL Injection and other common web attacks
 */

export function escapeHtml(unsafe) {
  if (typeof unsafe !== 'string') return ''
  
  const map = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#039;',
    '/': '&#x2F;'
  }
  
  return unsafe.replace(/[&<>"'/]/g, char => map[char])
}

export function sanitizeInput(input, options = {}) {
  if (typeof input !== 'string') return ''
  
  const { allowHtml = false, maxLength = 1000, trimWhitespace = true } = options
  
  let sanitized = input
  
  if (trimWhitespace) {
    sanitized = sanitized.trim()
  }
  
  if (sanitized.length > maxLength) {
    console.warn('[Security] Input truncated:', sanitized.length, '->', maxLength)
    sanitized = sanitized.substring(0, maxLength)
  }
  
  if (!allowHtml) {
    sanitized = sanitized.replace(/<script[^>]*>[\s\S]*?<\/script>/gi, '')
    sanitized = sanitized.replace(/\son\w+\s*=\s*["'][^"']*["']/gi, '')
    sanitized = escapeHtml(sanitized)
  }
  
  sanitized = sanitized.replace(/\0/g, '')
  sanitized = sanitized.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, '')
  
  return sanitized
}

export function validatePhone(phone) {
  if (!phone || typeof phone !== 'string') {
    return { valid: false, message: 'Please enter phone number' }
  }
  
  const cleaned = phone.replace(/[\s\-]/g, '')
  const phoneRegex = /^1[3-9]\d{9}$/
  
  if (!phoneRegex.test(cleaned)) {
    return { valid: false, message: 'Please enter valid 11-digit phone number' }
  }
  
  return { valid: true, value: cleaned }
}

export function validatePassword(password, requirements = {}) {
  const {
    minLength = 6,
    maxLength = 32,
    requireNumber = true,
    requireUppercase = false,
    requireLowercase = false,
    requireSpecialChar = false
  } = requirements
  
  if (!password || typeof password !== 'string') {
    return { valid: false, strength: 0, message: 'Please enter password' }
  }
  
  if (password.length < minLength) {
    return { valid: false, strength: 0, message: `Password must be at least ${minLength} characters` }
  }
  
  if (password.length > maxLength) {
    return { valid: false, strength: 0, message: `Password must not exceed ${maxLength} characters` }
  }
  
  let strength = 0
  const issues = []
  
  if (/\d/.test(password)) {
    strength += 1
  } else if (requireNumber) {
    issues.push('number')
  }
  
  if (/[A-Z]/.test(password)) {
    strength += 1
  } else if (requireUppercase) {
    issues.push('uppercase letter')
  }
  
  if (/[a-z]/.test(password)) {
    strength += 1
  } else if (requireLowercase) {
    issues.push('lowercase letter')
  }
  
  if (/[!@#$%^&*(),.?":{}|<>]/.test(password)) {
    strength += 1
  } else if (requireSpecialChar) {
    issues.push('special character')
  }
  
  if (password.length >= 12) strength += 1
  if (password.length >= 16) strength += 1
  
  if (issues.length > 0) {
    return { valid: false, strength, message: `Password must contain: ${issues.join(', ')}` }
  }
  
  let level = 'weak'
  if (strength >= 5) level = 'very strong'
  else if (strength >= 4) level = 'strong'
  else if (strength >= 3) level = 'medium'
  
  return { valid: true, strength, level, message: `Password strength: ${level}` }
}

export function checkSqlInjection(input) {
  if (typeof input !== 'string') {
    return { safe: true }
  }
  
  const dangerWords = ['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'DROP', 'ALTER', 'CREATE', 'EXEC', 'UNION']
  const upperInput = input.toUpperCase()
  
  for (let i = 0; i < dangerWords.length; i++) {
    if (upperInput.indexOf(dangerWords[i]) !== -1) {
      return { safe: false, warning: 'SQL injection detected' }
    }
  }
  
  if (upperInput.indexOf('--') !== -1 || upperInput.indexOf('/*') !== -1) {
    return { safe: false, warning: 'SQL comment injection detected' }
  }
  
  return { safe: true }
}

export function detectXssAttack(input) {
  if (typeof input !== 'string') return { safe: true }
  
  const xssPatterns = [
    { type: 'script tag', pattern: /<script[\s\S]*?>[\s\S]*?<\/script>/i },
    { type: 'event handler', pattern: /\bon\w+\s*=\s*["'][^"']*["']/i },
    { type: 'javascript protocol', pattern: /javascript\s*:/i },
    { type: 'data url', pattern: /data\s*:\s*text\/html/i },
    { type: 'css expression', pattern: /expression\s*\(/i },
    { type: 'vbscript', pattern: /vbscript\s*:/i }
  ]
  
  for (let i = 0; i < xssPatterns.length; i++) {
    const item = xssPatterns[i]
    if (item.pattern.test(input)) {
      console.warn('[Security] XSS attack detected:', item.type)
      return { safe: false, type: item.type, sanitized: sanitizeInput(input) }
    }
  }
  
  return { safe: true }
}

export function generateCsrfToken(length = 32) {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
  const charCount = chars.length
  const maxValidValue = Math.floor(256 / charCount) * charCount
  
  let token = ''
  const array = new Uint8Array(length)
  crypto.getRandomValues(array)
  
  for (let i = 0; i < length; i++) {
    let randomValue = array[i]
    
    while (randomValue >= maxValidValue) {
      const newArray = new Uint8Array(1)
      crypto.getRandomValues(newArray)
      randomValue = newArray[0]
    }
    
    token += chars[randomValue % charCount]
  }
  
  return token
}

export function maskSensitiveInfo(info, type = 'phone') {
  if (!info || typeof info !== 'string') return ''
  
  switch (type) {
    case 'phone':
      if (info.length >= 7) {
        return info.substring(0, 3) + '****' + info.substring(info.length - 4)
      }
      return info
      
    case 'email':
      const parts = info.split('@')
      if (parts[0] && parts[1]) {
        return parts[0][0] + '***@' + parts[1]
      }
      return info
      
    case 'idcard':
      if (info.length >= 14) {
        return info.substring(0, 3) + '***********' + info.substring(info.length - 4)
      }
      return info
      
    case 'name':
      if (info.length >= 2) {
        return info[0] + '*'.repeat(info.length - 1)
      }
      return '*'
      
    default:
      return info
  }
}

export function debounce(func, delay = 300) {
  let timer = null
  return function (...args) {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      func.apply(this, args)
      timer = null
    }, delay)
  }
}

export function throttle(func, limit = 500) {
  let inThrottle = false
  return function (...args) {
    if (!inThrottle) {
      func.apply(this, args)
      inThrottle = true
      setTimeout(() => { inThrottle = false }, limit)
    }
  }
}
