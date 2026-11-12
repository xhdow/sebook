# 合并算法（伪代码）

本文档用伪代码描述 `NodeTree.merge(NodeTree other)` 的实现，参考代码在
`src/main/java/notebook/tree/NodeTree.java` 的 `mergeObject` / `stampAdded` /
`clamp` / `normativeTs`。

术语与符号：

```
C         当前笔记树（current，函数式地“合并进它”）
S         准备导入的外部树（imported source）
now       本次合并开始时读取的一次性时间戳（System.currentTimeMillis()）
ts(N)     节点 N 的 "ts" 字段值
path(N)   节点 N 从根开始的路径，根为 "/"
owner(K)  持有键 K 的那个对象节点（K 可能是叶子，也可能是一个子节点）
node       一个 JSON 对象（字典），其 "ts" 键是保留字段
leaf       一个非对象的键值对（字符串/数字/布尔/数组/null）
```

树的形状：每个节点就是一个 JSON 对象，键 `"ts"` 存时间戳（epoch 毫秒），
其余值为对象的键是子节点，其余值为叶子。没有独立的 children 字段。

两条贯穿全文的不变量：

1. 节点按**从根开始的完整路径**对齐，同一条路径就视为“同一个东西”。
2. 合并后的任何节点都必须满足
   `ts = min(max(ts(S), ts(C)), now)`：取双方较新的那个，但绝不能超过 now。

还有一条总原则：**合并只做加法，从不删除**。任何只在一方存在的路径都会被保留，
不管是节点还是叶子。

---

## 入口

```
merge(S):
    now = 当前时间()
    stats = 一个新的计数器（全 0）
    # 在 C 上递归合并；返回合并后的整棵树和“是否有改动”
    result = mergeObject(C.root, S.root, now, stats)
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

`mergeObject` 处理双方都存在的同一路径，返回 `(node, changed)`。

```
mergeObject(here = C 的该节点, other = S 的该节点, now, stats):
    tsC = ts(here)
    tsS = ts(other)
    out = here 的深拷贝                          # 在副本上改，失败时原树不受影响
    changed = false

    ─────────────────────────────────────────────────────────────
    第一遍：遍历 S 这边的每个键（子节点与叶子都算）
    ─────────────────────────────────────────────────────────────
    for (name, theirs) in other 的所有非 "ts" 键:
        mine = here[name]

        # 情形 1：S 这边是个子节点
        if theirs 是对象:
            if mine 存在且是对象:
                # 双方同一路径都是节点 —— 核心规则
                # 绝不能因为 tsS > tsC 就把整棵子树替换掉，
                # 因为时间戳新只说明“它改过”，不说明“后代全都更新”。
                # 正确做法是继续往下逐键合并（取并集）。
                sub = mergeObject(mine, theirs, now, stats)
                out[name] = sub.node
                changed |= sub.changed

            elif mine 存在（是叶子，类型冲突）:
                # 一边是节点、一边是叶子：由拥有它的父节点谁更新来决定
                if tsS > tsC:
                    out[name] = stampAdded(theirs, tsS, now, stats)
                    changed = true
                else:
                    stats.kept += 1          # 保留 C 的叶子

            else:                            # mine 不存在
                # 路径在 C 中不存在 —— 直接把 S 的整棵子树搬过来
                out[name] = stampAdded(theirs, tsS, now, stats)
                changed = true

        # 情形 2：S 这边是个叶子
        else:
            if mine 不存在:
                out[name] = theirs           # C 没有：直接加入
                stats.valuesAdded += 1
                changed = true

            elif mine 是对象（类型冲突，另一边是节点）:
                if tsS > tsC:
                    out[name] = theirs
                    stats.valuesAdded += 1
                    changed = true
                else:
                    stats.kept += 1          # 保留 C 的节点

            elif mine == theirs:
                stats.valuesKept += 1        # 内容相同：什么都不做（保留 C 的 ts）

            elif tsS > tsC:
                out[name] = theirs           # 双方都有但不同：较新的一方胜出
                stats.valuesUpdated += 1
                changed = true

            else:
                stats.valuesKept += 1        # 相等或 C 更新：保留 C

    # 不再有第二遍“删除”逻辑。
    # 只在 C 这边存在的键无论 ts 如何都原样保留，只记入统计：
    for name in here 的所有非 "ts" 键:
        if S 中没有这个 name:
            stats.kept += 1

    ─────────────────────────────────────────────────────────────
    收尾：给这个节点定 ts
    ─────────────────────────────────────────────────────────────
    if not changed:
        # 本节点及其整棵子树都没变：保留 C 自己的 ts（只做未来的截断）
        out.ts = clamp(tsC, now, stats)
        return (out, false)

    # 有改动：本节点取双方较新者，再截断到 now
    out.ts = normativeTs(tsS, tsC, now, stats)
    return (out, true)
```

---

## 辅助函数

```
# 把一个 C 中不存在的节点（连同后代）整体搬入，同时对每个节点做未来截断。
# 整棵子树计为“新增”，因此这里的 ts 取的是各节点原本的 ts，而不是 max。
stampAdded(node, nodeTs, now, stats):
    node.ts = clamp(nodeTs, now, stats)
    stats.added += 1
    for (key, value) in node 的所有非 "ts" 键:
        if value 是对象:
            stampAdded(value, ts(value), now, stats)
        else:
            stats.valuesAdded += 1
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

**为什么中间节点时间戳更新不能整棵替换。**
设 S 的 `/work` 是 9000，C 的 `/work` 是 5000，但 C 的 `/work/deep` 是 400，
S 的 `/work/deep` 是 100，而且 C 的 `/work/deep` 下有个 S 没有的笔记。
`ts(S.work) > ts(C.work)` 只说明 S 在 work 这一层改过东西，
不能说明 deep 子树里所有内容都更新了。直接替换会丢掉那条独有的笔记。
所以只要同一个路径双方都是节点，就递归合并取并集。

**为什么只做加法、绝不根据“缺失”推断删除。**
两份笔记很可能从一开始就各自记录了不同的东西：C 里有 `google/projectA`，
S 里有 `google/projectB`，它们根本不是同一个任务。S 里没有 `projectA`，
只说明 S 没记过它，完全不等于“它该消失”。更糟的是，
`ts(parent)` 会因为任何后代更新而变新，于是“S 那边 `google` 变新了”
这种再普通不过的情况，就可能被误判成“S 删除了 `projectA`”，
静默丢掉一份只在本地存在的笔记，而且导入后很难察觉。
因此合并只增不删：要删除内容必须显式使用 `.rm` / `.del`。

**为什么“保留”不更新时间戳。**
`NodeTree` 的约定是“把值设成相同的值不算修改、不动 ts”。
合并中所有“保留 C”的分支都只是没做出改动，因此沿用 C 原有的 ts；
这样重复导入同一个文件是幂等的（结果 JSON 不变、不标脏）。

**为什么最后是 min(..., now)。**
两个文件可能来自不同机器，时钟可能偏快；`ts` 一旦出现未来时间，
之后的正常编辑（用本机当前时间）永远比它旧，就被永久压住了。
所以合并写入的任何 ts 都截断到本次合并的开始时间。
