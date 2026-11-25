import type { LandmarkPoint3D } from '../model/types';

class LowPassFilter {
  private state = 0;
  private initialized = false;

  filter(value: number, nextAlpha: number): number {
    const clampedAlpha = Math.max(0, Math.min(1, nextAlpha));
    const output = this.initialized
      ? clampedAlpha * value + (1 - clampedAlpha) * this.state
      : value;
    this.state = output;
    this.initialized = true;
    return output;
  }

  isInitialized(): boolean {
    return this.initialized;
  }

  lastOutput(): number {
    return this.state;
  }

  reset(): void {
    this.state = 0;
    this.initialized = false;
  }
}

/**
 * Adaptive 1-Euro low-pass filter (Casiez et al., CHI 2012) using monotonic microsecond timestamps.
 */
export class OneEuroFilter {
  private readonly minCutoffHz: number;
  private readonly beta: number;
  private readonly derivativeCutoffHz: number;
  private readonly nominalFps: number;
  private readonly valueFilter = new LowPassFilter();
  private readonly derivativeFilter = new LowPassFilter();
  private lastTimestampUs: number | null = null;

  constructor(
    minCutoffHz = 1.0,
    beta = 0.007,
    derivativeCutoffHz = 1.0,
    nominalFps = 30.0,
  ) {
    if (!Number.isFinite(minCutoffHz) || minCutoffHz <= 0) {
      throw new Error('minCutoffHz must be > 0');
    }
    if (!Number.isFinite(beta) || beta < 0) {
      throw new Error('beta must be >= 0');
    }
    if (!Number.isFinite(derivativeCutoffHz) || derivativeCutoffHz <= 0) {
      throw new Error('derivativeCutoffHz must be > 0');
    }
    if (!Number.isFinite(nominalFps) || nominalFps <= 0) {
      throw new Error('nominalFps must be > 0');
    }
    this.minCutoffHz = minCutoffHz;
    this.beta = beta;
    this.derivativeCutoffHz = derivativeCutoffHz;
    this.nominalFps = nominalFps;
  }

  filter(value: number, timestampUs: number): number {
    if (!Number.isFinite(value)) {
      throw new Error(`Filtered value must be finite (got ${value})`);
    }
    if (!this.valueFilter.isInitialized() || this.lastTimestampUs === null) {
      this.lastTimestampUs = timestampUs;
      this.derivativeFilter.filter(0, this.alpha(this.derivativeCutoffHz, this.nominalFps));
      return this.valueFilter.filter(value, this.alpha(this.minCutoffHz, this.nominalFps));
    }
    if (timestampUs <= this.lastTimestampUs) {
      throw new Error(
        `Timestamps must be strictly increasing (prev=${this.lastTimestampUs}, current=${timestampUs})`,
      );
    }

    const dtSeconds = (timestampUs - this.lastTimestampUs) / 1_000_000;
    const currentFreqHz = 1 / dtSeconds;
    this.lastTimestampUs = timestampUs;

    const rawDerivative = (value - this.valueFilter.lastOutput()) * currentFreqHz;
    const filteredDerivative = this.derivativeFilter.filter(
      rawDerivative,
      this.alpha(this.derivativeCutoffHz, currentFreqHz),
    );
    const adaptiveCutoffHz = this.minCutoffHz + this.beta * Math.abs(filteredDerivative);
    return this.valueFilter.filter(value, this.alpha(adaptiveCutoffHz, currentFreqHz));
  }

  reset(): void {
    this.valueFilter.reset();
    this.derivativeFilter.reset();
    this.lastTimestampUs = null;
  }

  private alpha(cutoffHz: number, freqHz: number): number {
    const tau = 1 / (2 * Math.PI * cutoffHz);
    const te = 1 / freqHz;
    return 1 / (1 + tau / te);
  }
}

/**
 * 3D landmark smoother tuned for MediaPipe normalized `[0..1]` landmark coordinates
 * (`beta = 3.0` raises cutoff responsiveness during fast ballistic take-off and landing).
 */
export class LandmarkSmoother3D {
  private readonly xFilters: OneEuroFilter[];
  private readonly yFilters: OneEuroFilter[];
  private readonly zFilters: OneEuroFilter[];

  constructor(landmarkCount: number, minCutoffHz = 1.0, beta = 3.0) {
    this.xFilters = Array.from({ length: landmarkCount }, () => new OneEuroFilter(minCutoffHz, beta));
    this.yFilters = Array.from({ length: landmarkCount }, () => new OneEuroFilter(minCutoffHz, beta));
    this.zFilters = Array.from({ length: landmarkCount }, () => new OneEuroFilter(minCutoffHz, beta));
  }

  smooth(landmarks: LandmarkPoint3D[], timestampUs: number): LandmarkPoint3D[] {
    if (landmarks.length !== this.xFilters.length) {
      throw new Error(`Expected ${this.xFilters.length} landmarks, got ${landmarks.length}`);
    }
    return landmarks.map((lm, i) => {
      // Do not poison filter state with occluded/hallucinated coordinates.
      if (lm.visibility < 0.25) {
        this.xFilters[i].reset();
        this.yFilters[i].reset();
        this.zFilters[i].reset();
        return { ...lm };
      }
      return {
        x: this.xFilters[i].filter(lm.x, timestampUs),
        y: this.yFilters[i].filter(lm.y, timestampUs),
        z: this.zFilters[i].filter(lm.z, timestampUs),
        visibility: lm.visibility,
      };
    });
  }

  reset(): void {
    for (let i = 0; i < this.xFilters.length; i += 1) {
      this.xFilters[i].reset();
      this.yFilters[i].reset();
      this.zFilters[i].reset();
    }
  }
}
