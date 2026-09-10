package intaglio.interaction

import intaglio.SemanticId
import scala.compiletime.testing.typeCheckErrors

class InteractionIdentitySuite extends munit.FunSuite:
  private def ok[A](value: Either[InteractionError, A]): A =
    value.fold(error => fail(error.message), identity)

  private val space = ok(KeySpace("observations", KeyCodec.integer))
  private val revision = ok(DataRevision("sha-001"))
  private val planRevision = ok(PlanRevision("geometry-001"))
  private def key(value: Int): EntityKey[Int] = ok(space.entity(value))

  test("entity identity survives reorder and filter without consulting source equality") {
    final class Row(val id: Int):
      override def equals(other: Any): Boolean = other != null
      override def hashCode(): Int = 0
    val rows = Vector(new Row(19), new Row(4), new Row(81))
    val original = ok(EntityIndex.build(space, rows)(_.id))
    val changed = ok(EntityIndex.build(space, Vector(rows(2), rows(0)))(_.id))
    assertEquals(original.keys.map(_.value), Vector(19, 4, 81))
    assertEquals(changed.indexOf(original.keys(0)), Some(1))
    assertEquals(changed.indexOf(original.keys(1)), None)
    assertEquals(changed.indexOf(original.keys(2)), Some(0))
  }

  test("duplicate entity keys fail with source positions; link keys intentionally repeat") {
    assertEquals(
      EntityIndex.build(space, Vector(19, 4, 19))(identity),
      Left(InteractionError.DuplicateEntity(0, 2))
    )
    assertEquals(ok(space.link(19)), ok(space.link(19)))
    assert(!key(19).equals(ok(space.link(19))))
  }

  test("same labels do not join independently constructed key spaces") {
    val other = ok(KeySpace("observations", KeyCodec.integer))
    assertNotEquals(key(19), ok(other.entity(19)))
    assertEquals(ok(space.readEntity(ok(other.entity(19)).token)), key(19))
    val foreign = ok(KeySpace("different-dataset", KeyCodec.integer))
    assertEquals(
      space.readEntity(ok(foreign.entity(19)).token),
      Left(InteractionError.KeySpaceMismatch("observations", "different-dataset"))
    )
  }

  test("transport checks key schema, revision and canonical payload") {
    val token = key(19).token
    assertEquals(ok(space.readEntity(token)), key(19))
    assertEquals(
      space.readEntity(token.copy(payload = "019")),
      Left(InteractionError.NonCanonicalKey)
    )
    assert(space.readEntity(token.copy(version = 2)).isLeft)
    assert(space.readEntity(token.copy(codec = "text")).isLeft)
    assert(space.readEntity(token.copy(payload = "2147483648")).isLeft)
    assert(space.readEntity(token.copy(payload = null)).isLeft)
  }

  test("text keys preserve literal Unicode and markup characters without interpreting them") {
    val text = ok(KeySpace("labels", KeyCodec.text))
    Vector("", "Ångström", "a\"<&>b", "a\nb", "😀", " leading and trailing ").foreach { value =>
      val entity = ok(text.entity(value))
      assertEquals(entity.token.payload, value)
      assertEquals(ok(text.readEntity(entity.token)).value, value)
    }
  }

  test("independently specified permutations and subsets preserve key lookup") {
    val original = ok(EntityIndex.build(space, Vector.tabulate(97)(i => i * 7 - 300))(identity))
    (0 until 97).foreach { offset =>
      // Multiplication by 31 permutes residues modulo prime 97.
      val indices = Vector.tabulate(97)(i => (i * 31 + offset) % 97).filter(_ % 3 != 0)
      val values = indices.map(i => i * 7 - 300)
      val changed = ok(EntityIndex.build(space, values)(identity))
      original.keys.indices.foreach { sourceIndex =>
        val expected = indices.indexOf(sourceIndex) match
          case -1       => None
          case position => Some(position)
        assertEquals(changed.indexOf(original.keys(sourceIndex)), expected)
      }
    }
  }

  test("codec and accessor exceptions stay in the typed error channel") {
    val throwing = ok(
      KeyCodec[Int]("throwing", 1)(_ => throw new IllegalStateException("encode"), _ => Right(0))
    )
    assertEquals(
      ok(KeySpace("test", throwing)).entity(1),
      Left(InteractionError.CallbackFailed("encode"))
    )
    val decoder = ok(
      KeyCodec[Int]("throwing-read", 1)(
        n => Right(n.toString),
        _ => throw new IllegalStateException("decode")
      )
    )
    assertEquals(
      ok(KeySpace("test", decoder)).entity(1),
      Left(InteractionError.CallbackFailed("decode"))
    )
    assertEquals(
      EntityIndex.build(space, Vector(1))(_ => throw new IllegalStateException("row")),
      Left(InteractionError.CallbackFailed("entity key accessor"))
    )
  }

  test("codec configuration rejects invalid names and versions") {
    assert(KeyCodec[Int]("bad name", 1)(n => Right(n.toString), _ => Right(0)).isLeft)
    assert(KeyCodec[Int]("valid", 0)(n => Right(n.toString), _ => Right(0)).isLeft)
    assert(KeySpace(null, KeyCodec.text).isLeft)
    assert(DataRevision("  ").isLeft)
    assert(DataRevision(null).isLeft)
  }

  test("visual target identity is plan-local and constant-size batches check their bounds") {
    val plan = SemanticId.unsafe("plot-a")
    val panel = SemanticId.unsafe("layer-one-panel-two")
    val targets = ok(TargetSeries(plan, planRevision, panel, 100000))
    assertEquals(ok(targets.at(99999)).ordinal, 99999)
    assertEquals(targets.at(100000), Left(InteractionError.IndexOutOfBounds(100000, 100000)))
    assertEquals(targets.at(-1), Left(InteractionError.IndexOutOfBounds(-1, 100000)))
    val next = ok(TargetSeries(plan, ok(PlanRevision("geometry-002")), panel, 100000))
    assertNotEquals(ok(targets.at(1)), ok(next.at(1)))
    val last = ok(TargetSeries(plan, planRevision, panel, 1, Int.MaxValue))
    assertEquals(ok(last.at(0)).ordinal, Int.MaxValue)
    assert(TargetSeries(plan, planRevision, panel, 2, Int.MaxValue).isLeft)
    assert(TargetSeries(plan, planRevision, panel, -1).isLeft)
    assert(ok(TargetSeries(plan, planRevision, panel, 0)).at(0).isLeft)
  }

  test("incomplete provenance never yields an exact selection, even for zero or one member") {
    val pending = ok(Membership.deferred(space, revision, 2, SemanticId.unsafe("server-members")))
    val memberships = Vector(
      Membership.unavailable(space, revision),
      ok(Membership.countOnly(space, revision, 0)),
      ok(Membership.representative(space, revision, 1, key(1))),
      ok(Membership.partial(space, revision, 2, Vector(key(1)))),
      pending
    )
    memberships.foreach { membership =>
      assertEquals(
        membership.exactKeys(revision),
        Left(InteractionError.MembershipUnavailable(membership.capability))
      )
    }
    assertEquals(pending.resolver, Some(SemanticId.unsafe("server-members")))
  }

  test("exact membership preserves declared keys and rejects stale source revisions") {
    val expected = Vector(19, 4, 81).map(key)
    val members = ok(Membership.exact(space, revision, expected))
    assertEquals(ok(members.exactKeys(revision)), expected)
    assertEquals(members.total, Some(3))
    assertEquals(
      members.exactKeys(ok(DataRevision("sha-002"))),
      Left(InteractionError.StaleRevision("sha-001", "sha-002"))
    )
    assertEquals(
      ok(Membership.exact(space, revision, Vector.empty)).exactKeys(revision),
      Right(Vector.empty)
    )
  }

  test("membership rejects invalid totals, duplicates, and foreign key-space witnesses") {
    assert(Membership.countOnly(space, revision, -1).isLeft)
    assert(Membership.representative(space, revision, 0, key(1)).isLeft)
    assert(Membership.partial(space, revision, 1, Vector(key(1))).isLeft)
    assertEquals(
      Membership.exact(space, revision, Vector(key(1), key(1))),
      Left(InteractionError.DuplicateEntity(0, 1))
    )
    val other = ok(KeySpace("observations", KeyCodec.integer))
    assertEquals(
      Membership.exact(space, revision, Vector(ok(other.entity(1)))),
      Left(InteractionError.ForeignKey(0))
    )
  }

  test("entity, grouping, and key-value types remain distinct at compile time") {
    assert(typeCheckErrors("""
      import intaglio.interaction.*
      def consume(key: EntityKey[Int]): Unit = ()
      def wrong(key: LinkKey[Int]): Unit = consume(key)
    """).nonEmpty)
    assert(typeCheckErrors("""
      import intaglio.interaction.*
      def consume(key: EntityKey[Int]): Unit = ()
      def wrong(key: EntityKey[String]): Unit = consume(key)
    """).nonEmpty)
    assert(typeCheckErrors("""
      import intaglio.interaction.*
      def forge(target: VisualTargetId) = target.copy(ordinal = -1)
    """).nonEmpty)
    assert(typeCheckErrors("""
      import intaglio.interaction.*
      def forge(members: Membership[Int]) = members.copy(total = Some(-1))
    """).nonEmpty)
  }
