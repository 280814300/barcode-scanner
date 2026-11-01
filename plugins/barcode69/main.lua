-- 69码商品识别插件 v1.0.0
-- 触发：on_scan（每次扫码识别后由宿主调用），rec = {value, format, timestamp, extras}

function is_ean13(v)
  if #v ~= 13 then return false end
  for i = 1, 13 do
    local c = v:sub(i, i)
    if c < '0' or c > '9' then return false end
  end
  return true
end

-- 中国商品条码前缀 690-699
function is_69(v)
  return is_ean13(v) and v:sub(1, 3) >= '690' and v:sub(1, 3) <= '699'
end

function on_scan(rec)
  local v = rec.value
  if not is_69(v) then return end
  -- 已识别过商品则跳过，避免重复弹窗
  if rec.extras and rec.extras.product then return end

  local p = host.product.lookup(v)
  if p then
    host.update(v, { product = p.name, spec = p.spec, unit = p.unit, price = p.price })
    host.toast("商品：" .. tostring(p.name))
    return
  end

  -- 未收录：弹窗录入
  host.ui.openForm("录入商品", {
    { key = "name",  label = "商品名称", type = "text" },
    { key = "spec",  label = "规格",     type = "text" },
    { key = "unit",  label = "单位",     type = "text" },
    { key = "price", label = "单价(元)", type = "number" }
  }, function(r)
    local price = tonumber(r.price) or 0
    host.product.add(v, { name = r.name, spec = r.spec, unit = r.unit, price = price })
    host.update(v, { product = r.name, spec = r.spec, unit = r.unit, price = price })
    host.toast("已收录：" .. tostring(r.name))
  end)
end
