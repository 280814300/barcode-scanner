-- 批量盘点插件 v1.0.0
-- on_menu 开始/结束盘点；on_scan 计数+1；on_record_longclick 计数-1 纠错

function get_counts()
  local raw = host.storage.get("counts") or "{}"
  local t = host.json.parse(raw)
  if t == nil or type(t) ~= "table" then t = {} end
  return t
end

function save_counts(t)
  host.storage.set("counts", host.json.encode(t))
end

function on_menu()
  local mode = host.storage.get("mode")
  if mode ~= "counting" then
    host.storage.set("mode", "counting")
    host.storage.set("counts", "{}")
    host.toast("盘点开始：逐件扫描计数，再次点菜单结束导出")
    return
  end
  -- 结束并导出实盘
  local counts = get_counts()
  local rows = {}
  table.insert(rows, { "条码", "数量" })
  local keys = {}
  for k, _ in pairs(counts) do table.insert(keys, k) end
  table.sort(keys)
  for _, k in ipairs(keys) do
    table.insert(rows, { k, tostring(counts[k]) })
  end
  host.export.csv("stocktake_" .. tostring(host.time()) .. ".csv", rows)
  host.storage.set("mode", "idle")
  host.toast("盘点结束，已导出实盘 CSV")
end

function on_scan(rec)
  if host.storage.get("mode") ~= "counting" then return end
  local counts = get_counts()
  counts[rec.value] = (counts[rec.value] or 0) + 1
  save_counts(counts)
  host.toast("已扫 " .. rec.value .. " x" .. tostring(counts[rec.value]))
end

function on_record_longclick(rec)
  if host.storage.get("mode") ~= "counting" then return end
  local counts = get_counts()
  if counts[rec.value] then
    counts[rec.value] = counts[rec.value] - 1
    if counts[rec.value] <= 0 then counts[rec.value] = nil end
    save_counts(counts)
    host.toast("纠正 -1：" .. rec.value)
  end
end
