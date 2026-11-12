# 合并算法（伪代码）

本文档用伪代码描述 `NodeTree.merge(NodeTree other)` 的实现，参考代码在
`src/main/java/sebook/tree/NodeTree.java` 的 `mergeNode` / `mergeValue` /
`mergeDirectory` / `stampAdded` / `clamp` / `normativeTs`。

术语与符号：

```
C         当前笔记树（current，函数式地“合并进它”）
S         准备导入的外部树（imported source）
now       本次合并开始时读取的一次性时间戳（System.currentTimeMillis()）
ts(N)     节点 N 自己的 "ts" 字段值
path(N)   节点 N 从根开始的路径，根为 "/"
dir       目录节点：没有 nt，或 nt="d"
value     值节点：nt="v"，其数据放在 "val" 里（可以是任意 JSON）
```

树的形状：每个条目都是一个 JSON 对象，`"ts"` 存它自己的时间戳（epoch 毫秒）。
`"nt"` 决定类型：

```json
{
  "ts": 5000, "nt": "d",
  "google": {
    "ts": 5000, "nt": "d",
    "a1": {
      "ts": 5000, "nt": "d",
      "user":     { "ts": 1000, "nt": "v", "val": "tom" },
      "password": { "ts": 8000, "nt": "v", "val": "12345" }
    }
  }
}
```

关键点：**每个值都有自己的 ts**，所以同一目录下的 `user` 和 `password`
可以各自独立地被修改和判断新旧。`ts` / `nt` / `val` 三个键是保留字，
不会出现在子节点的名字里。

两条贯穿全文的不变量：

1. 节点按**从根开始的完整路径**对齐，同一条路径就视为“同一个东西”。
2. 合并后的任何节点都必须满足
   `ts = min(max(ts(S), ts(C)), now)`：取双方较新的那个，但绝不能超过 now。

还有一条总原则：**合并只做加法，从不删除**。任何只在一方存在的路径都会被保留，
不管是目录还是值。

---

## 入口

```
merge(S):
    now = 当前时间()
    stats = 一个新的计数器（全 0）
    # 在 C 上递归合并；返回合并后的整棵树和“是否有改动”
    result = mergeNode(C.root, S.root, now, stats)
    cwd = "/"                                   # 合并后工作目录回到根

    if result.changed:                          # 有内容真正变了
        C.root 就地替换为 result.node           # 就地改，保持已拿到的引用有效
        C.dirty = true                          # 标记未保存
    else:
        # 内容没变，但仍然不能让根带着“未来时间戳”
        C.root.ts = clamp(C.root.ts, now, stats)

    return stats
```

说明：

- `changed = false` 表示合并结果与 C 完全一致，此时**不标脏、不改任何 ts**
  （把“保留”当作一次“设置相同的值”，与 `NodeTree` 既有语义一致）。
- 返回的 `stats` 只用于向用户展示与测试，不影响合并结果。

---

## 单个节点

`mergeNode` 处理双方都存在的同一路径，先按类型分派，返回 `(node, changed)`。

```
mergeNode(here = C 的该节点, other = S 的该节点, now, stats):

    if here 是 value 且 other 是 value:
        return mergeValue(here, other, now, stats)

    if here 是 dir 且 other 是 dir:
        return mergeDirectory(here, other, now, stats)

    # 类型冲突：一边是目录、一边是值。
    # 目录和值不可能共存，只能整体二选一：谁的 ts 新谁胜（平手保留 C 的形状）。
    if ts(other) > ts(here):
        out = stampAdded(other 的深拷贝, now, stats)
        stats.updated += 1
        return (out, true)
    else:
        stats.kept += 1
        return (here 的深拷贝, false)
```

说明同一个值的两个要点：比较用的是**值节点自己的 ts**，不是父目录的 ts；
所以同一目录下的多个值互不影响。

```
mergeValue(here, other, now, stats):
    tsC = ts(here)
    tsS = ts(other)

    if here.val == other.val:
        # 内容一样，什么都不改，保留 C 自己的 ts
        stats.valuesKept += 1
        stats.kept += 1
        return (here 的深拷贝（ts 做未来截断）, false)

    if tsS > tsC:
        # S 的值更新：整个值节点换成 S 的，ts 取 min(max(S,C),now)
        out = other 的深拷贝
        out.ts = normativeTs(tsS, tsC, now, stats)
        stats.valuesUpdated += 1
        stats.updated += 1
        return (out, true)

    # C 的值更新或平手：保留 C
    stats.valuesKept += 1
    stats.kept += 1
    return (here 的深拷贝（ts 做未来截断）, false)
```

目录则逐键取并集。注意每个条目的新旧判断都交给下一层的
`mergeNode`/`mergeValue`，本层只决定“这个键在不在”。

```
mergeDirectory(here, other, now, stats):
    tsC = ts(here)
    tsS = ts(other)
    out = here 的深拷贝
    changed = false

    # 遍历 S 这边的每个键（子目录和值都算）
    for (name, theirs) in other 的所有非保留键:
        mine = here[name]

        if mine 不存在:
            # 路径在 C 中不存在 —— 直接把 S 的整棵子树搬过来
            out[name] = stampAdded(theirs 的深拷贝, now, stats)
            changed = true
            continue

        # 双方同一路径都有 —— 核心规则：
        # 绝不能因为 tsS > tsC 就把整棵子树替换掉，
        # 因为目录的 ts 新只说明“它下面某处改过”，不说明“后代全都更新”。
        # 必须继续往下合并，让每个值按它自己的 ts 决定胜负。
        sub = mergeNode(mine, theirs, now, stats)
        if sub.node != mine:
            out[name] = sub.node
        changed |= sub.changed

    # 只在 C 这边存在的键无论 ts 如何都原样保留，只记入统计（只做加法）：
    for name in here 的所有非保留键:
        if S 中没有这个 name:
            stats.kept += 1

    if not changed:
        # 本目录及其整棵子树都没变：保留 C 自己的 ts（只做未来截断）
        out.ts = clamp(tsC, now, stats)
        stats.kept += 1
        return (out, false)

    # 有改动：本目录取双方较新者，再截断到 now
    out.ts = normativeTs(tsS, tsC, now, stats)
    stats.updated += 1
    return (out, true)
```

---

## 辅助函数

```
# 把一个 C 中不存在的节点（连同后代）整体搬入，同时对每个节点做未来截断。
# 整棵子树计为“新增”，因此这里的 ts 取的是各节点原本的 ts，而不是 max。
stampAdded(node, now, stats):
    node.ts = clamp(node.ts, now, stats)
    if node 是 value:
        stats.valuesAdded += 1
        return node
    stats.added += 1
    for (key, value) in node 的所有非保留键:
        stampAdded(value, now, stats)
    return node


# 不允许未来时间戳：比 now 还大的 ts 一律压到 now。
clamp(ts, now, stats):
    if ts > now:
        stats.normativeTs += 1        # 统计“被截断了几次”，仅用于展示
        return now
    return ts


# 合并节点最终的 ts 规则：min(max(S, C), now)
normativeTs(tsS, tsC, now, stats):
    return clamp(max(tsS, tsC), now, stats)
```

---

## 为什么这样设计

**为什么值必须有目己的 ts。**
旧模型里值只是父目录下的一个普通键，没有自己的时间戳。于是同一目录下的
`user` 和 `password` 共用父目录的 ts：C 改了 `user`、S 改了 `password` 后，
两边父目录的 ts 都变新了，合并时无法判断到底哪个值更新，只能整体取舍，
必然丢掉一边的修改。给每个值单独的 `ts` 之后，两个值各自比较、各自取舍，
“我改用户名、你改密码”这种最常见的并行修改可以同时保留。

**为什么目录时间戳更新不能整棵替换。**
设 S 的 `/work` 是 9000，C 的 `/work` 是 5000，但 C 的 `/work/deep` 是 400，
S 的 `/work/deep` 是 100，而且 C 的 `/work/deep` 下有个 S 没有的笔记。
`ts(S.work) > ts(C.work)` 只说明 S 在 work 这一层改过东西，
不能说明 deep 子树里所有内容都更新了。直接替换会丢掉那条独有的笔记。
所以只要同一个路径双方都是目录，就递归合并取并集，让最底层每个值
按自己的 ts 决定胜负。

**为什么只做加法、绝不根据“缺失”推断删除。**
两份笔记很可能从一开始就各自记录了不同的东西：C 里有 `google/projectA`，
S 里有 `google/projectB`，它们根本不是同一个任务。S 里没有 `projectA`，
只说明 S 没记过它，完全不等于“它该消失”。更糟的是，
`ts(parent)` 会因为任何后代更新而变新，于是“S 那边 `google` 变新了”
这种再普通不过的情况，就可能被误判成“S 删除了 `projectA`”，
静默丢掉一份只在本地存在的笔记，而且导入后很难察觉。
因此合并只增不删：要删除内容必须显式使用 `.rm`。

**为什么“保留”不更新时间戳。**
`NodeTree` 的约定是“把值设成相同的值不算修改、不动 ts”。
合并中所有“保留 C”的分支都只是没做出改动，因此沿用 C 原有的 ts；
这样重复导入同一个文件是幂等的（结果 JSON 不变、不标脏）。

**为什么最后是 min(..., now)。**
两个文件可能来自不同机器，时钟可能偏快；`ts` 一旦出现未来时间，
之后的正常编辑（用本机当前时间）永远比它旧，就被永久压住了。
所以合并写入的任何 ts 都截断到本次合并的开始时间。
