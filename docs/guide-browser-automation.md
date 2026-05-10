# 浏览器自动化（Playwright）安装与使用指南

> 本文档记录在 Rbot（Android chroot Ubuntu）环境中安装配置 Playwright 浏览器自动化的完整流程，以及踩过的坑。

## 一、概述

在 Rbot 的 chroot Ubuntu 环境中，我们使用 **Playwright** 作为浏览器自动化工具，原因如下：

| 工具 | 是否可用 | 原因 |
|------|---------|------|
| agent-browser | ❌ 不可用 | Chrome for Testing 不支持 Linux ARM64 |
| Playwright | ✅ 可用 | 支持 Linux ARM64，pip 安装即可 |
| Selenium | ⚠️ 可用但不推荐 | 需要额外安装浏览器驱动 |

## 二、安装

### 2.1 安装 Python Playwright

```bash
pip3 install playwright --break-system-packages
```

> ⚠️ 在 chroot Ubuntu 中，pip 默认不允许修改系统包，需要加 `--break-system-packages` 参数。

### 2.2 安装 Chromium 浏览器

```bash
python3 -m playwright install chromium
```

首次安装会下载 Chromium 浏览器文件，可能需要几分钟。

### 2.3 安装系统依赖

```bash
apt-get install -y \
  libatk1.0-0t64 \
  libatk-bridge2.0-0t64 \
  libcups2t64 \
  libasound2t64 \
  libgbm1 \
  libcairo2 \
  libpango-1.0-0 \
  libxcomposite1 \
  libxdamage1 \
  libxfixes3 \
  libxrandr2 \
  libatspi2.0-0t64
```

## 三、验证安装

```python
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    page.goto('https://www.baidu.com')
    print('页面标题:', page.title())
    browser.close()
    print('✅ Playwright Chromium 运行成功！')
```

输出：

```
页面标题: 百度一下，你就知道
✅ Playwright Chromium 运行成功！
```

## 四、核心用法

### 4.1 基本流程

```python
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    # 1. 启动浏览器（headless=True 为无头模式）
    browser = p.chromium.launch(headless=True)
    
    # 2. 创建新页面
    page = browser.new_page()
    
    # 3. 导航到目标 URL
    page.goto('https://example.com')
    
    # 4. 与页面交互...
    
    # 5. 关闭浏览器
    browser.close()
```

### 4.2 页面截图

```python
page.screenshot(path='screenshot.png')          # 可视区域截图
page.screenshot(path='full.png', full_page=True)  # 整页截图
```

### 4.3 元素操作

```python
# 点击元素
page.click('button#submit')
page.click('text=登录')

# 填写表单
page.fill('input#username', 'admin')
page.fill('input#password', '123456')

# 选择下拉框
page.select_option('select#country', 'CN')

# 勾选复选框
page.check('input#agree')

# 按键
page.press('Enter')
page.press('Control+a')
```

### 4.4 获取页面内容

```python
# 获取文本
title = page.title()
text = page.inner_text('h1')
content = page.inner_text('.article')

# 获取 HTML
html = page.content()

# 获取属性
href = page.get_attribute('a.link', 'href')

# 获取多个元素
items = page.query_selector_all('.list-item')
for item in items:
    print(item.inner_text())
```

### 4.5 等待机制

```python
# 等待元素出现
page.wait_for_selector('.result')

# 等待导航完成
page.wait_for_load_state('networkidle')

# 等待固定时间（不推荐，尽量用上面的方法）
page.wait_for_timeout(3000)

# 等待弹窗
page.wait_for_event('popup')
```

### 4.6 执行 JavaScript

```python
# 执行 JS 并获取返回值
result = page.evaluate('document.title')
count = page.evaluate('document.querySelectorAll("img").length')

# 执行多行 JS
data = page.evaluate('''() => {
    return {
        title: document.title,
        url: location.href,
        links: Array.from(document.querySelectorAll('a')).map(a => a.href)
    }
}''')
```

### 4.7 处理弹窗和对话框

```python
# 处理 alert
page.on('dialog', lambda dialog: dialog.accept())

# 处理 confirm
page.on('dialog', lambda dialog: dialog.accept() if dialog.message == '确认？' else dialog.dismiss())
```

### 4.8 模拟移动设备

```python
# 模拟 iPhone 12
iphone = p.devices['iPhone 12']
context = browser.new_context(**iphone)
page = context.new_page()
page.goto('https://m.baidu.com')
```

## 五、实战示例

### 5.1 搜索百度并获取结果

```python
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    page.goto('https://www.baidu.com')
    
    # 输入搜索关键词
    page.fill('#kw', 'Playwright 教程')
    page.click('#su')
    
    # 等待结果加载
    page.wait_for_selector('.result')
    
    # 获取搜索结果
    results = page.query_selector_all('.result')
    for i, result in enumerate(results[:10], 1):
        title = result.query_selector('h3')
        if title:
            print(f'{i}. {title.inner_text()}')
    
    browser.close()
```

### 5.2 GitHub 仓库信息获取

```python
import json
from playwright.sync_api import sync_playwright

def get_github_repo_info(owner, repo):
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page()
        page.goto(f'https://github.com/{owner}/{repo}')
        page.wait_for_timeout(2000)
        
        data = page.evaluate('''() => {
            return {
                name: document.title,
                description: document.querySelector('[itemprop="description"]')?.inner_text?.trim(),
                stars: document.querySelector('a[href$="/stargazers"] strong')?.inner_text?.trim(),
                language: document.querySelector('[itemprop="programmingLanguage"]')?.inner_text?.trim()
            }
        }''')
        
        browser.close()
        return data

info = get_github_repo_info('openclaw', 'openclaw')
print(json.dumps(info, indent=2, ensure_ascii=False))
```

### 5.3 网页表单自动填写

```python
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    
    # 打开登录页
    page.goto('https://example.com/login')
    
    # 填写表单
    page.fill('input[name="username"]', 'admin')
    page.fill('input[name="password"]', 'password123')
    page.check('input[name="remember"]')
    
    # 提交
    page.click('button[type="submit"]')
    
    # 等待跳转
    page.wait_for_url('**/dashboard')
    
    print('登录成功，当前页面:', page.title())
    browser.close()
```

## 六、踩坑记录

### 坑 1：pip 安装报错 "externally-managed-environment"

**现象**：`pip3 install playwright` 报错。

**原因**：chroot Ubuntu 的 Python 是系统管理的，pip 默认不允许安装包。

**修复**：

```bash
pip3 install playwright --break-system-packages
```

### 坑 2：缺少系统依赖

**现象**：`python3 -m playwright install chromium` 后运行报错，提示缺少 libatk 等库。

**原因**：Chromium 需要一些系统级的图形和音频库。

**修复**：

```bash
apt-get install -y libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 \
  libasound2t64 libgbm1 libcairo2 libpango-1.0-0 libxcomposite1 \
  libxdamage1 libxfixes3 libxrandr2 libatspi2.0-0t64
```

### 坑 3：agent-browser 不可用

**现象**：安装 agent-browser 后无法运行，提示 Chrome for Testing 不支持 Linux ARM64。

**原因**：agent-browser 依赖 Chrome for Testing，该版本不提供 ARM64 Linux 构建。

**修复**：改用 Playwright，它支持 ARM64 架构。

### 坑 4：headless 模式下某些网站无法访问

**现象**：部分网站检测到无头浏览器后拒绝访问。

**修复**：

```python
browser = p.chromium.launch(
    headless=True,
    args=['--disable-blink-features=AutomationControlled']
)
context = browser.new_context(
    user_agent='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
)
```

## 七、在 AstrBot 中使用

```python
import os
import subprocess

def browser_search(keyword):
    """在 AstrBot 插件中使用 Playwright 搜索"""
    script = f"""
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    page.goto('https://www.baidu.com')
    page.fill('#kw', '{keyword}')
    page.click('#su')
    page.wait_for_selector('.result')
    results = page.query_selector_all('.result')
    for i, r in enumerate(results[:5], 1):
        title = r.query_selector('h3')
        if title:
            print(f'{{i}}. {{title.inner_text()}}')
    browser.close()
"""
    result = subprocess.run(
        ['python3', '-c', script],
        capture_output=True, text=True,
        env={**os.environ, 'HOME': '/root'}
    )
    return result.stdout
```

