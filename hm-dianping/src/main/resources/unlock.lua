-- 这里的 KEYS[1] 就是锁的key，这里的ARGV[1] 就是当前线程标示
-- 1. 获取锁中的线程标识
local lockThreadId = redis.call("get", KEYS[1])
-- 当前线程的标识
local threadId = ARGV[1]
-- 2. 判断与当前线程标识是否一致
if (lockThreadId == threadId) then
    -- 3. 是则释放锁
    return redis.call("del", KEYS[1])
end
-- 4. 不是则不释放锁
return 0