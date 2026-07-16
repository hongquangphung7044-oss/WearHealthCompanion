"""
Java's java.util.Random 的精确 Python 夑现，用于复现 Kotlin syntheticEcg 信号。
Java LCG: seed = (seed * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
nextDouble(): ((next(26) << 27) + next(27)) / (1 << 53)
"""
import math


class JavaRandom:
    def __init__(self, seed):
        # Java: self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)
        self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)
        self._gaussian_next = None  # for nextGaussian sync state

    def next(self, bits):
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        # Java: return (int)(self.seed >>> (48 - bits))
        return self.seed >> (48 - bits)

    def nextDouble(self):
        hi = self.next(26)
        lo = self.next(27)
        # Java: return (((long)next(26) << 27) + next(27)) * (1.0 / (1L << 53))
        # Must use multiplication (not division) to match Java's float rounding
        return ((hi << 27) + lo) * (1.0 / float(1 << 53))

    def nextGaussian(self):
        # Java's nextGaussian (synchronized Box-Muller)
        if self._gaussian_next is not None:
            g = self._gaussian_next
            self._gaussian_next = None
            return g
        while True:
            x1 = 2 * self.nextDouble() - 1
            x2 = 2 * self.nextDouble() - 1
            s = x1 * x1 + x2 * x2
            if s < 1 and s != 0:
                break
        multiplier = math.sqrt(-2 * math.log(s) / s)
        self._gaussian_next = x2 * multiplier
        return x1 * multiplier


def kotlin_synthetic_ecg(duration_sec, r_peak_times, r_amp_mv=1.0, noise_level=0.02,
                         seed=42, r_sigma=0.01, t_amp_mv=0, t_offset=0.3):
    """精确复现 Kotlin syntheticEcg（含 JavaRandom）"""
    sr = 500
    total = int(duration_sec * sr)
    rng = JavaRandom(seed)
    sigma = r_sigma
    sigma_sq2 = 2 * sigma * sigma
    t_sigma = 0.04
    t_sigma_sq2 = 2 * t_sigma * t_sigma
    data = []
    for i in range(total):
        t = i / sr
        v = 0.0
        for rT in r_peak_times:
            dt = t - rT
            v += r_amp_mv * math.exp(-(dt * dt) / sigma_sq2)
            if t_amp_mv != 0:
                tDt = t - rT - t_offset
                tSign = t_amp_mv if r_amp_mv > 0 else -t_amp_mv
                v += tSign * math.exp(-(tDt * tDt) / t_sigma_sq2)
        v += (rng.nextDouble() - 0.5) * 2 * noise_level
        data.append(int(v * 1000))
    return data


if __name__ == "__main__":
    # 验证 JavaRandom 是否匹配 Java
    r = JavaRandom(42)
    vals = [r.nextDouble() for _ in range(5)]
    print("JavaRandom(42) first 5 nextDouble:", vals)
    # Java 预期（用 jshell 或 Kotlin 验证）:
    # java.util.Random(42).nextDouble() = 0.7275635439978564
    # 0.9728932645751012, 0.1600463144400254, 0.4770145239335881, 0.2617085401839608
