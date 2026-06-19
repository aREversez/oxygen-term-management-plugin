# Oxygen XML Editor 插件开发需求：Term Management

## 项目概述

为 Oxygen XML Editor 开发一个术语翻译辅助与管理插件，名称为 **Term Management**。插件由两部分组成：**Preferences 配置面板**和**插件主窗口**。项目面向开源发布（GitHub），当前阶段不实现"将术语插入文档"功能。

---

## 技术约束

- 开发框架：Oxygen XML SDK（Java + Maven）
- 目标 Oxygen 版本：27/28（兼容 Java 17 JRE）
- 编译目标：Java 17（`maven.compiler.source/target = 17`）
- 打包方式：Maven `maven-assembly-plugin`，产出 fat jar 放入 `lib/`
- 插件描述文件：`plugin.xml` + `extension.xml`
- 持久化方式：Oxygen `OptionsStorage` API 存储术语库路径列表及启用状态
- UI 层：Java Swing（与 Oxygen 宿主一致）
- 国际化：`ResourceBundle` 加载 `i18n/messages_xx.properties`，UI 字符串不硬编码

---

## 项目目录结构

### 源码仓库结构（git）

```
term-management/
├── plugin.xml
├── extension.xml
├── LICENSE
├── README.md
├── pom.xml
├── .gitignore
│
├── i18n/
│   ├── messages_en.properties
│   └── messages_zh.properties
│
├── licenses/
│   ├── APACHE-POI-LICENSE.txt
│   └── OPENCSV-LICENSE.txt
│
├── src/main/java/com/example/termmgmt/
│   ├── TermManagementPlugin.java
│   ├── model/
│   │   ├── TermEntry.java
│   │   └── TermbaseConfig.java
│   ├── service/
│   │   ├── TermbaseLoader.java
│   │   ├── CsvTermbaseHandler.java
│   │   ├── XlsxTermbaseHandler.java
│   │   ├── TbxTermbaseHandler.java
│   │   └── TermbaseRegistry.java
│   ├── ui/
│   │   ├── TermManagementView.java
│   │   ├── TermRecognitionPanel.java
│   │   ├── TermbaseSearchPanel.java
│   │   ├── TerminologyPanel.java
│   │   └── TermEntryDialog.java
│   └── prefs/
│       └── TermManagementPreferencePage.java
│
└── output/                         # 本地编译输出，不入 git
    └── term-management/
        ├── plugin.xml
        ├── extension.xml
        ├── lib/
        │   └── term-management-1.0.0-jar-with-dependencies.jar
        ├── i18n/
        │   ├── messages_en.properties
        │   └── messages_zh.properties
        └── licenses/
            ├── APACHE-POI-LICENSE.txt
            └── OPENCSV-LICENSE.txt
```

### `.gitignore`

```gitignore
target/
output/
*.class
*.iml
.idea/
.settings/
.classpath
.project
```

### 编译输出包结构（部署即用）

`output/term-management/` 与 Oxygen `plugins/term-management/` 部署结构完全一致，直接复制进去重启 Oxygen 即生效：

```
plugins/
└── term-management/
    ├── plugin.xml
    ├── extension.xml
    ├── lib/
    │   └── term-management-1.0.0-jar-with-dependencies.jar
    ├── i18n/
    │   ├── messages_en.properties
    │   └── messages_zh.properties
    └── licenses/
        ├── APACHE-POI-LICENSE.txt
        └── OPENCSV-LICENSE.txt
```

---

## plugin.xml

```xml
<plugin
  id="com.example.termmgmt"
  name="Term Management"
  description="Terminology management and translation assistance plugin"
  version="1.0.0"
  vendor="YourName"
  classLoaderType="preferReferencedResources">

  <runtime>
    <library name="lib/term-management-1.0.0-jar-with-dependencies.jar"/>
  </runtime>

</plugin>
```

## extension.xml

```xml
<extensions>
  <extension type="WorkspaceAccess"
             class="com.example.termmgmt.TermManagementPlugin"/>
  <extension type="OptionPage"
             processorType="oxygen"
             class="com.example.termmgmt.prefs.TermManagementPreferencePage"/>
</extensions>
```

---

## 编译输出配置（pom.xml）

使用 `maven-antrun-plugin`，每次 `mvn package` 时将产出物自动复制到 `output/term-management/`，覆盖旧文件：

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-antrun-plugin</artifactId>
  <version>3.1.0</version>
  <executions>
    <execution>
      <id>copy-to-output</id>
      <phase>package</phase>
      <goals><goal>run</goal></goals>
      <configuration>
        <target>
          <mkdir dir="${project.basedir}/output/term-management/lib"/>
          <mkdir dir="${project.basedir}/output/term-management/i18n"/>
          <mkdir dir="${project.basedir}/output/term-management/licenses"/>
          <copy file="${project.build.directory}/${project.artifactId}-${project.version}-jar-with-dependencies.jar"
                todir="${project.basedir}/output/term-management/lib"
                overwrite="true"/>
          <copy file="${project.basedir}/plugin.xml"
                todir="${project.basedir}/output/term-management"
                overwrite="true"/>
          <copy file="${project.basedir}/extension.xml"
                todir="${project.basedir}/output/term-management"
                overwrite="true"/>
          <copy todir="${project.basedir}/output/term-management/i18n" overwrite="true">
            <fileset dir="${project.basedir}/i18n"/>
          </copy>
          <copy todir="${project.basedir}/output/term-management/licenses" overwrite="true">
            <fileset dir="${project.basedir}/licenses"/>
          </copy>
        </target>
      </configuration>
    </execution>
  </executions>
</plugin>
```

`overwrite="true"` 确保每次编译都覆盖旧文件，不使用时间戳，版本对比通过 git tag 管理。

---

## Maven 依赖

```xml
<dependencies>
  <dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.2.3</version>
  </dependency>
  <dependency>
    <groupId>com.opencsv</groupId>
    <artifactId>opencsv</artifactId>
    <version>5.7.1</version>
  </dependency>
</dependencies>
```

TBX 使用 JDK 内置 `javax.xml.parsers`（DOM），无需额外依赖。

---

## 模块一：Preferences 配置面板

### 入口路径

`Preferences > Plugins > Term Management`

### 说明文字

> *Add termbases and activate them for translation.*

### 术语库列表

`JTable`，每行显示以下字段：

| 字段 | 说明 |
|---|---|
| 文件名 | 仅显示文件名，不含路径 |
| 路径 | 完整绝对路径 |
| 格式 | 自动识别：TBX / XLSX / CSV |
| 状态 | Enabled / Disabled |

支持拖动行手动排序（`TransferHandler` + `DropTarget`）。

### 操作按钮

| 按钮 | 行为 |
|---|---|
| `Add` | 打开文件选择对话框，过滤 `.tbx` / `.xlsx` / `.csv`；将所选文件绝对路径加入列表，默认状态 Enabled |
| `Reload` | 重新从磁盘读取当前选中术语库，刷新内存缓存 |
| `Edit` | 调用 `Desktop.open(file)` 以系统默认程序打开当前选中术语库文件 |
| `Remove` | 从列表移除当前选中项，仅移除记录，不删除源文件 |
| `Enable` | 将选中项状态设为 Enabled |
| `Disable` | 将选中项状态设为 Disabled |

### 持久化

- 点击 `OK` / `Apply` 时，将路径列表、排列顺序、启用状态通过 `OptionsStorage` 序列化保存
- 插件加载时从 `OptionsStorage` 恢复完整列表

---

## 模块二：插件主窗口

### 窗口类型

Oxygen 侧边面板（`WorkspaceView`），通过菜单或工具栏触发显示。

### 布局

`JTabbedPane`，三个 Tab。

---

### Tab 1：Term Recognition（术语识别）

**功能**：分析当前编辑器打开文档，识别命中术语库的词条并列表展示。

**触发方式**：Tab 激活时或点击 `Scan` 按钮时执行。

**识别逻辑**：

1. 通过 `AuthorAccess` 或 `TextContentIterator` 获取当前文档纯文本
2. 遍历所有 Enabled 术语库的 source term 字段
3. 字符串匹配（大小写不敏感，全词匹配）
4. 将命中词条输出到结果列表

**结果列表列**：Source Term、Target Term、所属术语库文件名

**本阶段不实现**：点击术语插入到文档。

---

### Tab 2：Termbase Search（术语搜索）

**功能**：在所有 Enabled 术语库中搜索术语。

**UI 组件**：文本输入框 + `Search` 按钮 + 结果列表。

**结果列表列**：Source Term、Target Term、术语库文件名。

**搜索逻辑**：模糊匹配，同时检索 source term 和 target term，不区分大小写。

---

### Tab 3：Terminology（术语库管理）

**功能**：对单个 Enabled 术语库进行增删改查，所有编辑操作直接写回源文件。

#### 顶部：术语库选择

`JComboBox`，仅列出状态为 Enabled 的术语库；切换后刷新下方列表。

#### 术语列表

`JTable`，支持多选（`MULTIPLE_INTERVAL_SELECTION`）。

| 列 | 说明 |
|---|---|
| Source Term | 原语（源语言） |
| Target Term | 译语 |
| 其他字段 | 按文件实际字段展示，如 note、domain 等 |

#### 操作按钮及多选行为

| 按钮 | 多选行为 | 说明 |
|---|---|---|
| `Reload` | N/A | 重新从磁盘读取当前术语库，刷新列表 |
| `Add New Term` | 单次操作 | 弹出 `TermEntryDialog`，输入 Source Term + Target Term（+ 可选字段），确认后写入源文件 |
| `Quick Add New Term` | 单次操作 | 读取编辑器当前选中文本作为 Source Term 预填充，打开同上对话框；无选中文本则提示用户 |
| `Edit Term` | 仅允许单选 | 多选时提示"请仅选择一条术语进行编辑"；弹出字段预填充的 `TermEntryDialog`，确认后写入源文件 |
| `Delete Term` | 支持多选批量删除 | 弹出确认对话框（显示将删除的条数），确认后批量从源文件删除 |

---

## 术语库格式解析规范

### CSV

- 编码：UTF-8，第一行为表头
- **表头使用 BCP 47 语言标签**（小写），不使用 `source`/`target` 等语义名
- 中英术语库标准表头：`zh-cn`（第一列）、`en-us`（第二列）
- 可选附加列：`note`、`domain` 等
- 示例：

```
zh-cn,en-us,note
有限元分析,Finite Element Analysis,FEA
网格划分,Meshing,
```

- 解析规则：第一列固定为 source term，第二列固定为 target term，其余列作为附加字段
- 读写库：OpenCSV

### XLSX

- 读取第一个 Sheet，第一行为表头，**表头规则与 CSV 完全一致**
- 中英术语库标准表头：`zh-cn`（A 列）、`en-us`（B 列），可选附加列从 C 列起
- 解析规则：A 列（第一列）固定为 source term，B 列（第二列）固定为 target term
- 读写库：Apache POI

### TBX

- 标准 TBX（ISO 30042）格式，`xml:lang` 属性天然承载语言标签
- `<termEntry>` 为一条术语条目
- `<langSet xml:lang="zh-CN">` 下的 `<tig>` → `<term>` 内容为 source term
- `<langSet xml:lang="en-US">` 下的 `<tig>` → `<term>` 内容为 target term
- 同时支持 `<ntig>` → `<termGrp>` → `<term>` 结构
- `xml:lang` 匹配时做前缀匹配（不区分大小写），兼容 `zh`、`zh-CN`、`zh-cn` 等变体
- 读写：JDK 内置 `javax.xml.parsers`（DOM）

### 语言列识别逻辑（CSV / XLSX 通用）

1. **位置优先**：第一列为 source term，第二列为 target term，不依赖列名语义匹配
2. **语言标签记录**：表头值存入 `TermbaseConfig` 的 `sourceLang` / `targetLang` 字段，UI 列标题直接显示语言标签（如 `zh-cn`）而非通用名
3. **附加字段**：第三列起按实际表头名存入 `TermEntry` 扩展字段 Map

### 文件写回逻辑

| 格式 | 写入方式 |
|---|---|
| CSV | 读取全部行，内存修改后整体写回 |
| XLSX | 读取 Workbook，修改 Sheet，`FileOutputStream` 写回 |
| TBX | 解析 DOM，修改节点，序列化写回 |

所有写回操作完成后自动刷新内存缓存并更新列表视图。

---

## 不在本阶段实现的功能

- 将术语插入到当前编辑器文档
- Author 模式术语高亮
- 术语库导出
- 超过中英两列的多语言对支持
- `framework/` 文件夹（本插件为纯工具类，不涉及自定义文档类型）
