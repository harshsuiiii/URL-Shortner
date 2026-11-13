-- Token bucket, executed atomically inside Redis so concurrent app instances
-- cannot race on read-modify-write.
-- KEYS[1] = bucket key
-- ARGV[1] = capacity (max burst), ARGV[2] = refill rate (tokens/second)
-- Returns { allowed (0|1), tokens remaining, retry-after seconds }
local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])

-- Use Redis' clock, not the callers': app servers' clocks drift.
local t = redis.call('TIME')
local now = tonumber(t[1]) + tonumber(t[2]) / 1000000

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil then
  tokens = capacity
  ts = now
end

tokens = math.min(capacity, tokens + math.max(0, now - ts) * refill)

local allowed = 0
local retry_after = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry_after = math.ceil((1 - tokens) / refill)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- Idle buckets expire once they would have refilled completely anyway.
redis.call('EXPIRE', KEYS[1], math.ceil(capacity / refill) + 1)

return { allowed, math.floor(tokens), retry_after }
