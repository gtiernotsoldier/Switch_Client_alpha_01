//! Port of `io.doppel.core.safety`.
//!
//! The tracker is a circuit breaker over setbacks. The only subtle part is blame
//! attribution: the Kotlin sums movement magnitudes per module in a
//! `LinkedHashMap` and takes `maxByOrNull`, which resolves ties by insertion
//! order (first module to appear in the sample list wins). A Rust `HashMap`
//! iterates arbitrarily, so the same input could blame a different module on a
//! tie — and a tie is exactly what two modules moving equally would produce.
//! Hence the `Vec` and the strict `>` comparison below.

use crate::model::{SetbackEvent, SetbackVerdict, Vec3};

pub struct SetbackTracker {
    pub circuit_break_threshold: i32,
    pub lookback_ticks: i64,
    pub movement_epsilon: f64,
    total_setbacks: i32,
    blame_counts: Vec<(String, i32)>,
    circuit_broken: bool,
    circuit_broken_module: Option<String>,
}

impl Default for SetbackTracker {
    fn default() -> Self {
        SetbackTracker {
            circuit_break_threshold: 2,
            lookback_ticks: 10,
            movement_epsilon: 0.001,
            total_setbacks: 0,
            blame_counts: Vec::new(),
            circuit_broken: false,
            circuit_broken_module: None,
        }
    }
}

impl SetbackTracker {
    /// Insertion-ordered increment, mirroring the Kotlin map.
    fn bump(&mut self, module: &str) -> i32 {
        if let Some(e) = self.blame_counts.iter_mut().find(|(m, _)| m == module) {
            e.1 += 1;
            e.1
        } else {
            self.blame_counts.push((module.to_string(), 1));
            1
        }
    }

    pub fn get_blame_count(&self, module: &str) -> i32 {
        self.blame_counts
            .iter()
            .find(|(m, _)| m == module)
            .map(|(_, c)| *c)
            .unwrap_or(0)
    }

    pub fn total_setbacks(&self) -> i32 {
        self.total_setbacks
    }

    pub fn is_circuit_broken(&self) -> bool {
        self.circuit_broken
    }

    pub fn circuit_broken_module(&self) -> Option<&str> {
        self.circuit_broken_module.as_deref()
    }

    pub fn blame_counts(&self) -> &[(String, i32)] {
        &self.blame_counts
    }

    pub fn reset(&mut self) {
        self.total_setbacks = 0;
        self.blame_counts.clear();
        self.circuit_broken = false;
        self.circuit_broken_module = None;
    }

    pub fn record_setback(&mut self, event: &SetbackEvent) -> SetbackVerdict {
        if self.circuit_broken {
            return SetbackVerdict::CircuitBreak {
                module_id: self
                    .circuit_broken_module
                    .clone()
                    .unwrap_or_else(|| "unknown".to_string()),
                blame_count: self.total_setbacks,
            };
        }

        self.total_setbacks += 1;

        let blamed = self.attribute_blame(event);
        let blamed = match blamed {
            Some(b) => b,
            None => {
                return SetbackVerdict::ExternalCause {
                    tick: event.tick,
                    total_setbacks: self.total_setbacks,
                }
            }
        };

        let count = self.bump(&blamed);

        if count >= self.circuit_break_threshold {
            self.circuit_broken = true;
            self.circuit_broken_module = Some(blamed.clone());
            return SetbackVerdict::CircuitBreak {
                module_id: blamed,
                blame_count: count,
            };
        }

        SetbackVerdict::BlameModule {
            module_id: blamed,
            blame_count: count,
            threshold: self.circuit_break_threshold,
        }
    }

    fn attribute_blame(&self, event: &SetbackEvent) -> Option<String> {
        let lookback_start = event.tick - self.lookback_ticks;

        let relevant: Vec<&crate::model::ModuleMovementSample> = event
            .recent_movement_samples
            .iter()
            .filter(|s| s.tick >= lookback_start && s.tick <= event.tick)
            .collect();

        if relevant.is_empty() {
            return None;
        }

        // Preserve first-appearance order so ties resolve like the LinkedHashMap.
        let mut totals: Vec<(String, f64)> = Vec::new();
        for s in relevant {
            let magnitude = s.movement.length();
            if magnitude > self.movement_epsilon {
                if let Some(e) = totals.iter_mut().find(|(m, _)| *m == s.module_id) {
                    e.1 += magnitude;
                } else {
                    totals.push((s.module_id.clone(), magnitude));
                }
            }
        }

        if totals.is_empty() {
            return None;
        }

        let mut best: Option<(String, f64)> = None;
        for (m, v) in totals {
            match &best {
                None => best = Some((m, v)),
                Some((_, bv)) if v > *bv => best = Some((m, v)),
                _ => {}
            }
        }
        best.map(|(m, _)| m)
    }
}

/// `SafetyWrapper`'s failure counter, without the logging.
///
/// The Kotlin version catches exceptions and counts consecutive failures; Rust
/// gets the same shape out of `catch_unwind`, but that lives at the dispatch
/// site (`session.rs`), not here. What is worth keeping in the core is the
/// counting rule, because it decides *when* a module gets disabled.
pub struct FailureCounter {
    max_failures: i32,
    counts: Vec<(String, i32)>,
}

impl Default for FailureCounter {
    fn default() -> Self {
        FailureCounter { max_failures: 3, counts: Vec::new() }
    }
}

impl FailureCounter {
    pub fn max_failures(&self) -> i32 {
        self.max_failures
    }

    /// Returns the new consecutive count.
    pub fn record_failure(&mut self, module: &str) -> i32 {
        if let Some(e) = self.counts.iter_mut().find(|(m, _)| m == module) {
            e.1 += 1;
            e.1
        } else {
            self.counts.push((module.to_string(), 1));
            1
        }
    }

    pub fn reset_failures(&mut self, module: &str) {
        self.counts.retain(|(m, _)| m != module);
    }

    pub fn failure_count(&self, module: &str) -> i32 {
        self.counts
            .iter()
            .find(|(m, _)| m == module)
            .map(|(_, c)| *c)
            .unwrap_or(0)
    }

    pub fn should_disable(&self, module: &str) -> bool {
        self.failure_count(module) >= self.max_failures
    }

    pub fn is_degraded(&self, module: &str) -> bool {
        let c = self.failure_count(module);
        c >= 1 && c < self.max_failures
    }

    pub fn reset_all(&mut self) {
        self.counts.clear();
    }
}

/// Convenience constructor kept next to the tracker so callers do not have to
/// remember the sample-vector field name.
pub fn setback_event(
    tick: i64,
    before: Vec3,
    after: Vec3,
    samples: Vec<crate::model::ModuleMovementSample>,
) -> SetbackEvent {
    SetbackEvent {
        tick,
        position_before: before,
        position_after: after,
        recent_movement_samples: samples,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::ModuleMovementSample;

    fn sample(module: &str, tick: i64, x: f64) -> ModuleMovementSample {
        ModuleMovementSample {
            module_id: module.to_string(),
            tick,
            movement: Vec3::new(x, 0.0, 0.0),
        }
    }

    #[test]
    fn external_cause_when_nothing_moved() {
        let mut t = SetbackTracker::default();
        let ev = setback_event(100, Vec3::new(0.0, 64.0, 0.0), Vec3::new(1.0, 64.0, 0.0), vec![]);
        match t.record_setback(&ev) {
            SetbackVerdict::ExternalCause { .. } => {}
            other => panic!("expected external cause, got {:?}", other),
        }
        assert_eq!(t.total_setbacks(), 1);
    }

    #[test]
    fn blames_the_module_that_moved_most() {
        let mut t = SetbackTracker::default();
        let ev = setback_event(
            100,
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(5.0, 0.0, 0.0),
            vec![sample("Sprint", 95, 0.1), sample("Speed", 96, 2.0)],
        );
        match t.record_setback(&ev) {
            SetbackVerdict::BlameModule { module_id, blame_count, .. } => {
                assert_eq!(module_id, "Speed");
                assert_eq!(blame_count, 1);
            }
            other => panic!("expected blame, got {:?}", other),
        }
    }

    #[test]
    fn tie_goes_to_the_first_module_seen() {
        let mut t = SetbackTracker::default();
        let ev = setback_event(
            100,
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(1.0, 0.0, 0.0),
            vec![sample("First", 99, 0.5), sample("Second", 99, 0.5)],
        );
        match t.record_setback(&ev) {
            SetbackVerdict::BlameModule { module_id, .. } => assert_eq!(module_id, "First"),
            other => panic!("expected blame, got {:?}", other),
        }
    }

    #[test]
    fn samples_outside_the_lookback_are_ignored() {
        let mut t = SetbackTracker::default();
        let ev = setback_event(
            100,
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(1.0, 0.0, 0.0),
            vec![sample("Speed", 80, 3.0)],
        );
        assert!(matches!(t.record_setback(&ev), SetbackVerdict::ExternalCause { .. }));
    }

    #[test]
    fn second_blame_trips_the_breaker() {
        let mut t = SetbackTracker::default();
        let ev = setback_event(
            100,
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(1.0, 0.0, 0.0),
            vec![sample("Speed", 99, 1.0)],
        );
        assert!(matches!(t.record_setback(&ev), SetbackVerdict::BlameModule { .. }));
        match t.record_setback(&ev) {
            SetbackVerdict::CircuitBreak { module_id, blame_count } => {
                assert_eq!(module_id, "Speed");
                assert_eq!(blame_count, 2);
            }
            other => panic!("expected circuit break, got {:?}", other),
        }
        assert!(t.is_circuit_broken());
        // Further setbacks report the break rather than counting again.
        assert!(matches!(t.record_setback(&ev), SetbackVerdict::CircuitBreak { .. }));
        assert_eq!(t.total_setbacks(), 2);
    }

    #[test]
    fn failure_counter_disables_at_three() {
        let mut f = FailureCounter::default();
        assert!(!f.should_disable("Aim"));
        f.record_failure("Aim");
        assert!(f.is_degraded("Aim"));
        f.record_failure("Aim");
        assert!(f.is_degraded("Aim"));
        f.record_failure("Aim");
        assert!(f.should_disable("Aim"));
        f.reset_failures("Aim");
        assert!(!f.should_disable("Aim"));
    }
}
