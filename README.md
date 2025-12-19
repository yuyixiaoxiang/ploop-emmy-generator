# EmmyLua Annotation Generator for PLoop

自动为PLoop框架的Lua方法生成EmmyLua注释的Rider/IntelliJ插件。

## 功能

- 右键点击Lua方法 → 选择 "Generate EmmyLua Annotation"
- 快捷键: `Alt + E`
- 自动识别PLoop的class名称和继承关系
- 自动解析方法参数
- 根据参数命名约定推断类型
- 支持三种注释风格

## 生成的注释风格

### 1. 项目风格（推荐）
```lua
---@class hero_info_panel
---@field public RefreshUI fun(self:hero_info_panel,tab:number)
---@param self hero_info_panel
---@param tab number
function RefreshUI(self, tab)
```

### 2. 简化版本
```lua
---@param self hero_info_panel
---@param tab number
function RefreshUI(self, tab)
```

### 3. 完整版本
```lua
---@class hero_info_panel
---@field public RefreshUI fun(self:hero_info_panel,tab:number):void
---@param self hero_info_panel 
---@param tab number @tab
function RefreshUI(self, tab)
```

## 参数类型推断规则

| 参数命名模式 | 推断类型 |
|------------|---------|
| `self` | 当前class名 |
| `*Id`, `*_id` | number |
| `*Name`, `*_name` | string |
| `*List`, `*_list` | table |
| `*Dict`, `*_dict` | table |
| `*Data`, `*_data` | table |
| `is*`, `has*`, `can*` | boolean |
| `callback`, `cb`, `func` | function |
| `index`, `idx`, `count`, `num` | number |
| 其他 | any |

## 构建

```bash
# 进入项目目录
cd C:\Users\elex\RiderProjects\emmylua-annotation-generator

# 构建插件
gradlew buildPlugin

# 插件zip包位置
# build/distributions/emmylua-annotation-generator-1.0.0.zip
```

## 本地安装

### 方法1: 从zip安装（推荐）

1. 构建插件：`gradlew buildPlugin`
2. 打开Rider → File → Settings → Plugins
3. 点击齿轮图标 → Install Plugin from Disk...
4. 选择 `build/distributions/emmylua-annotation-generator-1.0.0.zip`
5. 重启Rider

### 方法2: 调试运行

```bash
# 启动带插件的Rider实例
gradlew runIde
```

## 使用方法

1. 在Rider中打开Lua文件
2. 将光标放在方法定义行（如 `function OnShow(self, params)`）
3. 右键 → Generate EmmyLua Annotation
4. 或使用快捷键 `Alt + E`
5. 选择注释风格
6. 注释将自动插入到方法定义上方

## 注意事项

- 确保Rider已安装EmmyLua插件
- 插件仅在 `.lua` 文件中启用
- 光标需要在 `function` 定义行上

## 开发

- JDK 17+
- Kotlin 1.9+
- IntelliJ Platform SDK
