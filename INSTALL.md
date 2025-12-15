# 安装指南

## 前置要求

1. **JDK 17+** - 确保已安装Java Development Kit 17或更高版本
   ```
   java -version
   ```

2. **Gradle** - 构建工具（可选，会自动下载）

## 快速构建与安装

### 方法1: 使用Rider直接打开项目

1. 打开Rider
2. File → Open → 选择 `C:\Users\elex\RiderProjects\emmylua-annotation-generator`
3. 等待Gradle同步完成
4. 在右侧Gradle面板中，双击 `Tasks → intellij → buildPlugin`
5. 构建完成后，插件包在 `build/distributions/` 目录

### 方法2: 命令行构建

```cmd
cd C:\Users\elex\RiderProjects\emmylua-annotation-generator

REM 如果没有安装Gradle，先安装Gradle Wrapper
REM 方式1: 使用已安装的Gradle生成Wrapper
gradle wrapper

REM 方式2: 或者直接下载gradlew
REM 可以从其他项目复制，或者使用Rider自动下载

REM 构建插件
gradlew.bat buildPlugin
```

### 方法3: 双击运行build.bat

1. 双击 `build.bat`
2. 按照提示操作

## 安装插件到Rider

1. 打开Rider
2. `File` → `Settings` (或 `Ctrl+Alt+S`)
3. 左侧选择 `Plugins`
4. 点击右上角齿轮图标 ⚙️
5. 选择 `Install Plugin from Disk...`
6. 找到并选择: `C:\Users\elex\RiderProjects\emmylua-annotation-generator\build\distributions\emmylua-annotation-generator-1.0.0.zip`
7. 点击 `OK`
8. 重启Rider

## 验证安装

1. 打开任意 `.lua` 文件
2. 将光标放在 `function` 定义行
3. 右键菜单中应该看到 `Generate EmmyLua Annotation`
4. 或者按 `Alt + E`

## 使用示例

**原始代码:**
```lua
Module "Game.View.test_panel" (function(_ENV)
    class "test_panel" (function(_ENV)
        inherit "ViewBase"
        
        function OnShow(self, params)
            -- 方法内容
        end
    end)
end)
```

**光标放在 `function OnShow(self, params)` 行，右键 → Generate EmmyLua Annotation**

**生成结果:**
```lua
Module "Game.View.test_panel" (function(_ENV)
    class "test_panel" (function(_ENV)
        inherit "ViewBase"
        
        ---@class test_panel
        ---@field public OnShow fun(self:test_panel,params:table) @方法描述
        ---@param self test_panel
        ---@param params table
        function OnShow(self, params)
            -- 方法内容
        end
    end)
end)
```

## 调试开发

如果你想修改插件并测试:

```cmd
cd C:\Users\elex\RiderProjects\emmylua-annotation-generator

REM 启动一个带有插件的Rider实例
gradlew.bat runIde
```

这会打开一个新的Rider窗口，里面已经加载了你的插件，可以直接测试。

## 常见问题

### Q: 构建失败，提示找不到JDK
A: 确保设置了 `JAVA_HOME` 环境变量指向JDK 17+

### Q: 右键菜单没有出现选项
A: 确保:
1. 文件是 `.lua` 扩展名
2. 光标在 `function xxx(...)` 这一行
3. 插件已正确安装并重启了Rider

### Q: 如何修改类型推断规则
A: 编辑 `PloopParser.kt` 中的 `inferParamType` 方法
