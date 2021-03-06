package com.twitter.finagle.toggle

import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.logging.{BareFormatter, Level, Logger, StringHandler}
import org.junit.runner.RunWith
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.junit.JUnitRunner
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FunSuite, Matchers}
import scala.collection.immutable
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class ToggleMapTest extends FunSuite
  with GeneratorDrivenPropertyChecks
  with Matchers {

  private val IntGen = arbitrary[Int]

  test("ToggleMap.observed produces a checksum summary") {
    val stats = new InMemoryStatsReceiver()
    val inMem = ToggleMap.newMutable()
    val map = ToggleMap.observed(inMem, stats)

    val gauge: () => Float = stats.gauges(Seq("checksum"))
    // these numbers were picked by observation and are used to
    // make sure they stay consistent across code changes.
    val initial = 0f
    val state1 = 1.19006272E9f
    val state2 = 3.58052019E9f

    // run it twice to make sure the checksum remains consistent
    // without changes
    assert(initial == gauge())
    assert(initial == gauge())

    val toggleName = "com.id"

    // validate checksum changes after an add
    inMem.put(toggleName, 0.0)
    assert(state1 == gauge())
    assert(state1 == gauge())

    // validate checksum changes after an update
    inMem.put(toggleName, 0.1)
    assert(state2 == gauge())
    assert(state2 == gauge())
  }

  test("ToggleMap.mutable") {
    val id = "com.toggle.hi"
    val m = ToggleMap.newMutable()
    assert(m.iterator.isEmpty)

    // start out empty
    val toggle = m(id)
    forAll(IntGen) { i =>
      assert(!toggle.isDefinedAt(i))
    }

    // update that toggle
    m.put(id, 1.0)
    forAll(IntGen) { i =>
      assert(toggle.isDefinedAt(i))
      assert(toggle(i))
    }

    // disable it.
    m.put(id, 0.0)
    forAll(IntGen) { i =>
      assert(toggle.isDefinedAt(i))
      assert(!toggle(i))
    }

    // renable, then remove it
    m.put(id, 1.0)
    assert(toggle(12333))
    m.remove(id)
    assert(!toggle.isDefinedAt(12333))
  }

  test("ToggleMap.mutable logs") {
    val id = "com.toggle.hi"
    def assertLog(log: String, fraction: String) = {
      log should include(id)
      log should include(fraction)
    }

    val handler = new StringHandler(BareFormatter, Some(Level.INFO))
    val logger = Logger.get(classOf[ToggleMap].getName)
    logger.addHandler(handler)

    val map = ToggleMap.newMutable()

    map.put(id, 0.0)
    assertLog(handler.get, "set to fraction=0.0")
    handler.clear()

    map.remove(id)
    assertLog(handler.get, "removed")
    handler.clear()

    map.put(id, 0.5)
    assertLog(handler.get, "set to fraction=0.5")
    handler.clear()

    map.put(id, 1.1)
    assertLog(handler.get, "ignoring invalid fraction=1.1")
    handler.clear()
  }

  test("ToggleMap.Immutable") {
    val map = new ToggleMap.Immutable(
      immutable.Seq(
        Toggle.Metadata("com.toggle.on", 1.0, None),
        Toggle.Metadata("com.toggle.off", 0.0, None)
      )
    )
    val on = map("com.toggle.on")
    val off = map("com.toggle.off")
    val doesntExist = map("com.toggle.ummm")
    forAll(IntGen) { i =>
      assert(on.isDefinedAt(i))
      assert(on(i))
      assert(off.isDefinedAt(i))
      assert(!off(i))
      assert(!doesntExist.isDefinedAt(i))
    }

    assert(map.iterator.size == 2)
    assert(map.iterator.exists(_.id == "com.toggle.on"))
    assert(map.iterator.exists(_.id == "com.toggle.off"))
  }

  test("ToggleMap.fractional") {
    val on = ToggleMap.fractional("com.toggle.on", 1.0)
    forAll(IntGen) { i =>
      assert(on(i))
    }

    val off = ToggleMap.fractional("com.toggle.off", 0.0)
    forAll(IntGen) { i =>
      assert(!off(i))
    }

    // given a big enough list of inputs, we should
    // get approximately the right `true` percentage as a result
    forAll(Gen.oneOf(0.001, 0.01, 0.1, 0.5, 0.9)) { fraction =>
      val toggle = ToggleMap.fractional(s"$fraction", fraction)

      // i'd like to use Gen.listOfN but N is the max size and it will
      // generate smaller lists. instead we pick some random sizes and use
      // that plus an RNG to generate the inputs to the toggle.
      val rng = new Random(919191L)
      forAll(Gen.choose(3000, 4500)) { size =>
        var trues = 0
        0.to(size).foreach { _ =>
          if (toggle(rng.nextInt()))
            trues += 1
        }

        // give ourselves an arbitrary 5% wiggle room
        // (but this may not be enough)
        val expected = size * fraction
        val epsilon = size * 0.05
        trues.toDouble should be(expected +- epsilon)
      }
    }
  }

  test("ToggleMap.of with no ToggleMaps") {
    assert(NullToggleMap == ToggleMap.of())
  }

  test("ToggleMap.Flags with empty Flags") {
    flag.overrides.let(Map.empty) {
      assert(ToggleMap.flags.iterator.isEmpty)

      forAll(ToggleGenerator.Id) { s =>
        val toggle = ToggleMap.flags(s)
        forAll(IntGen) { i =>
          assert(!toggle.isDefinedAt(i))
        }
      }
    }
  }

  private def containsFlagId(id: String, iter: Iterator[Toggle.Metadata]): Boolean =
    iter.exists(_.id == id)

  test("ToggleMap.Flags with populated Flags") {
    val map = Map(
      "com.toggle.on" -> 1.0,
      "com.toggle.off" -> 0.0,
      "com.toggle.some" -> 0.5
    )
    flag.overrides.let(map) {
      assert(containsFlagId("com.toggle.on", ToggleMap.flags.iterator))
      assert(containsFlagId("com.toggle.off", ToggleMap.flags.iterator))
      assert(containsFlagId("com.toggle.some", ToggleMap.flags.iterator))
      assert(!containsFlagId("com.toggle.nope", ToggleMap.flags.iterator))

      val ts = ToggleMap.flags.iterator.toSet
      assert(ts.size == 3)
      assert(ts.map(_.id) == map.keys.toSet)

      val on = ToggleMap.flags("com.toggle.on")
      forAll(IntGen) { i =>
        assert(on.isDefinedAt(i))
        assert(on(i))
      }
      val off = ToggleMap.flags("com.toggle.off")
      forAll(IntGen) { i =>
        assert(off.isDefinedAt(i))
        assert(!off(i))
      }

      // these results come from observed output and are subject
      // to change if the underlying algorithm changes. we want
      // some mechanism for seeing that the values can be variable.
      val someToggle = ToggleMap.flags("com.toggle.some")
      assert(someToggle.isDefinedAt(123))
      assert(!someToggle(123))
      assert(someToggle.isDefinedAt(Int.MinValue))
      assert(someToggle(Int.MinValue))
      assert(someToggle.isDefinedAt(Int.MaxValue))
      assert(someToggle(Int.MaxValue))
    }
  }

  test("ToggleMap.Flags ignores invalid fractions") {
    val map = Map(
      "com.toggle.wat" -> -0.1,
      "com.toggle.lol" -> 1.1
    )
    flag.overrides.let(map) {
      assert(ToggleMap.flags.iterator.isEmpty)
    }
  }

  test("ToggleMap.Flags Toggles see changes to the flag") {
    // make sure we start out cleared and using the flag returns false
    assert(!containsFlagId("com.toggle.on", ToggleMap.flags.iterator))
    val toggle = ToggleMap.flags("com.toggle.on")
    forAll(IntGen) { i =>
      assert(!toggle.isDefinedAt(i))
    }

    // now modify the flags and set it to 100%
    flag.overrides.let("com.toggle.on", 1.0) {
      assert(containsFlagId("com.toggle.on", ToggleMap.flags.iterator))
      forAll(IntGen) { i =>
        assert(toggle(i))
        assert(toggle.isDefinedAt(i))
      }

      // then nested within that, turn it off to 0%.
      flag.overrides.let("com.toggle.on", 0.0) {
        assert(containsFlagId("com.toggle.on", ToggleMap.flags.iterator))
        forAll(IntGen) { i =>
          assert(!toggle(i))
          assert(toggle.isDefinedAt(i))
        }
      }

      // then remove it via letClear
      flag.overrides.letClear("com.toggle.on") {
        assert(!containsFlagId("com.toggle.on", ToggleMap.flags.iterator))
        forAll(IntGen) { i =>
          assert(!toggle.isDefinedAt(i))
        }
      }
    }
    assert(!containsFlagId("com.toggle.on", ToggleMap.flags.iterator))
  }

  test("ToggleMap.orElse handles no Toggles") {
    assert(NullToggleMap.orElse(NullToggleMap).iterator.isEmpty)
  }

  test("ToggleMap.orElse sees Toggles from all ToggleMaps") {
    val tm0 = ToggleMap.newMutable()
    tm0.put("com.toggle.t0", 0.0)
    val tm1 = ToggleMap.newMutable()
    tm1.put("com.toggle.t1", 1.0)
    val tm2 = ToggleMap.newMutable()
    tm2.put("com.toggle.t2", 0.3)

    val tm01 = NullToggleMap.orElse(tm0).orElse(tm1)
    val tm012 = NullToggleMap.orElse(tm01).orElse(tm2)

    val mds01 = tm01.iterator.toSeq
    assert(mds01.size == 2)
    assert(mds01.exists { md => md.id == "com.toggle.t0" && md.fraction == 0.0 })
    assert(mds01.exists { md => md.id == "com.toggle.t1" && md.fraction == 1.0 })

    val mds012 = tm012.iterator.toSeq
    assert(mds012.size == 3)
    assert(mds012.exists { md => md.id == "com.toggle.t0" && md.fraction == 0.0 })
    assert(mds012.exists { md => md.id == "com.toggle.t1" && md.fraction == 1.0 })
    assert(mds012.exists { md => md.id == "com.toggle.t2" && md.fraction == 0.3 })
  }

  test("ToggleMap.orElse.iterator uses Toggles from earlier ToggleMaps") {
    val tm0 = ToggleMap.newMutable()
    tm0.put("com.toggle.t0", 0.0)

    val tm1 = ToggleMap.newMutable()
    tm1.put("com.toggle.t0", 1.0)

    val tm01 = NullToggleMap.orElse(tm0).orElse(tm1)
    val mds = tm01.iterator.toSeq
    assert(mds.size == 1)
    assert(mds.exists { md => md.id == "com.toggle.t0" && md.fraction == 0.0 }, mds)
  }

  test("ToggleMap.orElse.apply") {
    val tm0 = ToggleMap.newMutable()
    val tm1 = ToggleMap.newMutable()
    val tm01 = NullToggleMap.orElse(tm0).orElse(tm1)
    val toggle = tm01("com.toggle.t")

    // the toggle doesn't exist in either underlying map
    forAll(IntGen) { i =>
      assert(!toggle.isDefinedAt(i))
    }

    // the toggle doesn't yet exist in tm0
    // so we should use the value from tm1 (true)
    tm1.put("com.toggle.t", 1.0)
    forAll(IntGen) { i =>
      assert(toggle.isDefinedAt(i))
      assert(toggle(i))
    }

    // now, update it to 0% in tm0 which should it should use instead
    tm1.put("com.toggle.t", 0.0)
    forAll(IntGen) { i =>
      assert(toggle.isDefinedAt(i))
      assert(!toggle(i))
    }
  }

}
