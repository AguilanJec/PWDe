/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pwde;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for the full-screen reference projection used by the placement editor and the runtime
 * overlay. Normalized placements are fractions of the screen, so each axis must map linearly across
 * the whole viewport with no letterboxing or cropping.
 */
public class ReferenceProjectionTest {

  @Test
  public void pxFromNorm_linearAcrossFullViewport() {
    ReferenceProjection p = new ReferenceProjection(10f, 20f, 200f, 100f);
    assertEquals(10f, p.pxFromNormX(0f), 0.001f);
    assertEquals(110f, p.pxFromNormX(0.5f), 0.001f);
    assertEquals(210f, p.pxFromNormX(1f), 0.001f);
    assertEquals(20f, p.pxFromNormY(0f), 0.001f);
    assertEquals(70f, p.pxFromNormY(0.5f), 0.001f);
    assertEquals(120f, p.pxFromNormY(1f), 0.001f);
  }

  @Test
  public void pxToNorm_roundTrips() {
    ReferenceProjection p = new ReferenceProjection(10f, 20f, 2340f, 1080f);
    for (float nx : new float[] {0f, 0.15f, 0.5f, 0.86f, 1f}) {
      float px = p.pxFromNormX(nx);
      assertEquals(nx, p.normFromPxX(px), 1e-3f);
    }
    for (float ny : new float[] {0f, 0.31f, 0.5f, 0.82f, 1f}) {
      float py = p.pxFromNormY(ny);
      assertEquals(ny, p.normFromPxY(py), 1e-3f);
    }
  }

  @Test
  public void radius_fractionOfSmallerViewportDimension() {
    ReferenceProjection p = new ReferenceProjection(0f, 0f, 2340f, 1080f);
    assertEquals(0.15f * 1080f, p.screenRadius(0.15f), 0.001f);
    assertEquals(0f, p.screenRadius(0f), 0.001f);
    assertEquals(1080f, p.screenRadius(1f), 0.001f);
  }

  @Test
  public void destRect_isFullViewport() {
    ReferenceProjection p = new ReferenceProjection(5f, 7f, 200f, 100f);
    assertEquals(5f, p.destLeft(), 0.001f);
    assertEquals(7f, p.destTop(), 0.001f);
    assertEquals(200f, p.destWidth(), 0.001f);
    assertEquals(100f, p.destHeight(), 0.001f);
  }

  @Test
  public void minScreenDim_usesSmallerViewportDimension() {
    ReferenceProjection wide = new ReferenceProjection(0f, 0f, 200f, 100f);
    assertEquals(100f, wide.minScreenDim(), 0.001f);
    ReferenceProjection tall = new ReferenceProjection(0f, 0f, 100f, 200f);
    assertEquals(100f, tall.minScreenDim(), 0.001f);
  }
}
