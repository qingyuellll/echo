module.exports = {
  root: true,
  env: {
    browser: true,
    es2021: true,
    node: true
  },
  extends: [
    'eslint:recommended',
    'plugin:vue/vue3-recommended',
    '@typescript-eslint/recommended'
  ],
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module'
  },
  plugins: [
    'vue',
    '@typescript-eslint'
  ],
  rules: {
    // ==================== Strict Mode v2.0 规则 ====================
    
    // TypeScript 严格模式相关
    '@typescript-eslint/no-unused-vars': ['error', { 
      argsIgnorePattern: '^_',
      varsIgnorePattern: '^_'
    }],
    '@typescript-eslint/no-explicit-any': 'warn',
    '@typescript-eslint/explicit-function-return-type': 'off',
    '@typescript-eslint/no-non-null-assertion': 'warn',

    // Vue 最佳实践
    'vue/multi-word-component-names': 'off',
    'vue/require-default-prop': 'off',
    'vue/no-v-html': 'warn',

    // 代码质量
    'no-console': process.env.NODE_ENV === 'production' ? 'warn' : 'off',
    'no-debugger': process.env.NODE_ENV === 'production' ? 'error' : 'off',

    // ==================== 废弃字段检测规则（自定义） ====================
    
    // Show 实体废弃字段
    'no-restricted-properties': ['error', {
      object: 'show',
      property: 'posterUrl',
      message: '❌ show.posterUrl 已废弃，请使用 show.images 并解析第一张图片。参考：docs/FIELD_MIGRATION_GUIDE.md'
    }, {
      object: 'show',
      property: 'beginTime',
      message: '❌ show.beginTime 已废弃，请使用 show.openTime。参考：docs/FIELD_MIGRATION_GUIDE.md'
    }, {
      object: 'show',
      property: 'venue',
      message: '❌ show.venue 已废弃，请使用 show.address。参考：docs/FIELD_MIGRATION_GUIDE.md'
    }],

    // Ticket/Order 实体废弃字段
    'no-restricted-properties': ['error', {
      object: 'ticket',
      property: 'ticketName',
      message: '❌ ticket.ticketName 已废弃，请使用 ticket.title 或 order.ticketTitle。参考：docs/FIELD_MIGRATION_GUIDE.md'
    }, {
      object: 'order',
      property: 'ticketName',
      message: '❌ order.ticketName 已废弃，请使用 order.ticketTitle。参考：docs/FIELD_MIGRATION_GUIDE.md'
    }, {
      object: 'order',
      property: 'orderNo',
      message: '❌ order.orderNo 已废弃，请使用 order.id（雪花算法ID作为订单号）。参考：docs/FIELD_MIGRATION_GUIDE.md'
    }],

    // User 实体废弃字段
    'no-restricted-properties': ['error', {
      object: 'user',
      property: 'name',
      message: '❌ user.name 已废弃，请使用 user.nickName。参考：docs/FIELD_MIGRATION_GUIDE.md'
    }, {
      object: 'user',
      property: 'nickname',
      message: '❌ user.nickname 已废弃，请使用 user.nickName。参考：docs/FIELD_MIGRATION_GUIDE.md'
    }, {
      object: 'user',
      property: 'username',
      message: '❌ user.username 已废弃，请使用 user.nickName。参考：docs/FIELD_MIGRATION_GUIDE.md'
    }]
  },
  
  overrides: [
    {
      files: ['*.vue'],
      rules: {
        // Vue 文件特殊规则
        'vue/attribute-hyphenation': ['error', 'never'],
        'vue/v-on-event-hyphenation': ['error', 'never']
      }
    }
  ]
}
