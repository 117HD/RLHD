//
// Algorithm from "Analysis of Color Blindness" by Onur Fidaner, Poliang Lin and Nevran Ozguven.
// https://web.archive.org/web/20090731011248/http://scien.stanford.edu/class/psych221/projects/05/ofidaner/project_report.pdf
//

#include COLOR_BLINDNESS

#define NONE 0
#define PROTAN 1
#define DEUTERAN 2
#define TRITAN 3

const mat3 rgb2lms = mat3(
  vec3(17.8824, 43.5161, 4.11935),
  vec3(3.45565, 27.1554, 3.86714),
  vec3(0.0299566, 0.184309, 1.46709)
);

const mat3 lms2lmsp = mat3(
  vec3(0.0, 2.02344, -2.52581),
  vec3(0.0, 1.0, 0.0),
  vec3(0.0, 0.0, 1.0)
);

const mat3 lms2lmsd = mat3(
  vec3(1.0, 0.0, 0.0),
  vec3(0.494207, 0.0, 1.24827),
  vec3(0.0, 0.0, 1.0)
);

const mat3 lms2lmst = mat3(
  vec3(1.0, 0.0, 0.0),
  vec3(0.0, 1.0, 0.0),
  vec3(-0.395913, 0.801109, 0.0)
);

const mat3 protanopiaDaltonization = mat3(
  vec3(0.0, 0.0, 0.0),
  vec3(0.7, 1.0, 0.0),
  vec3(0.7, 0.0, 1.0)
);

#if COLOR_BLINDNESS != NONE
vec3 colorBlindnessCompensation(vec3 color)
{
  vec3 LMS = color * rgb2lms;

  #if COLOR_BLINDNESS == PROTAN
  vec3 lms = LMS * lms2lmsp; // red deficiency
  #elif COLOR_BLINDNESS == DEUTERAN
  vec3 lms = LMS * lms2lmsd; // green deficiency
  #elif COLOR_BLINDNESS == TRITAN
  vec3 lms = LMS * lms2lmst; // blue deficiency
  #endif

  // LMS to RGB matrix conversion
  mat3 lms2rgb = inverse(rgb2lms);
  vec3 error = lms * lms2rgb;

  // Isolate invisible colors to color vision deficiency (calculate error matrix)
  error = (color - error);

  // Shift colors towards visible spectrum (apply error modifications)
  vec3 compensation = error * protanopiaDaltonization;

  // Add compensation to original values
  compensation = color + compensation * colorBlindnessIntensity;

  return compensation;
}
#else
#define colorBlindnessCompensation(color) color
#endif
