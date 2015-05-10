package ammonite.interpreter

import scala.collection.mutable

class Imports(
  initialImports: Seq[(String, ImportData)] = Nil,
  useClassWrapper: Boolean = false
) {

  /**
   * Imports which are required by earlier commands to the REPL. Imports
   * have a specified key, so that later imports of the same name (e.g.
   * defining a variable twice) can kick the earlier import out of the
   * map. Otherwise if you import the same name twice you get compile
   * errors instead of the desired shadowing.
   */
  lazy val previousImports = mutable.Map(initialImports: _*)

  def previousImportBlock = {
    def isReplClassWrapImport(d: ImportData) =
      useClassWrapper && (d.prefix.startsWith(d.wrapperName + ".") || d.prefix == d.wrapperName)

    def transpose[A](xs: List[List[A]]): List[List[A]] = xs.filter(_.nonEmpty) match {
      case Nil    =>  Nil
      case ys: List[List[A]] => ys.map{ _.head }::transpose(ys.map{ _.tail })
    }

    def transformIfReplClassWrapImport(d: ImportData) =
      if (isReplClassWrapImport(d))
        d.copy(prefix = "$ref$" + d.prefix)
      else
        d

    val instanceRefs =
      for {
        prefix <- previousImports.values.toList.filter(isReplClassWrapImport).map(_.wrapperName).distinct.sorted
      } yield {
        s"val $$ref$$$prefix: $prefix.INSTANCE.$$user.type = $prefix.INSTANCE.$$user"
      }

    val snippets = for {
      (prefix, allImports) <- previousImports.values.toList.map(transformIfReplClassWrapImport).groupBy(_.prefix)
      imports <- transpose(allImports.groupBy(_.fromName).values.toList).reverse
    } yield {
      imports match{
        case Seq(imp) if imp.fromName == imp.toName =>
          s"import ${imp.prefix}.${BacktickWrap(imp.fromName)}"
        case imports =>
          val lines = for (x <- imports if !x.toName.endsWith("_$eq")) yield {
            if (x.fromName == x.toName)
              "\n  " + BacktickWrap(x.fromName)
            else
              "\n  " + BacktickWrap(x.fromName) + " => " + (if (x.toName == "_") "_" else BacktickWrap(x.toName))

          }
          val block = lines.mkString(",")
          s"import $prefix.{$block\n}"
      }
    }

    instanceRefs.mkString("\n") + "\n" + snippets.mkString("\n")
  }

  def update(newImports: Seq[ImportData]): Unit = {
    val newImports0 =
      if (useClassWrapper) {
        newImports.map { d =>
          if (d.prefix.startsWith(d.wrapperName + ".$ref$")) {
            // Assuming this is an import through previous REPL variables
            val stripped = d.prefix.stripPrefix(d.wrapperName + ".$ref$")
            d.copy(prefix = stripped, wrapperName = stripped.takeWhile(_ != '.'))
          } else
            d
        }
      } else
        newImports

    for(i <- newImports0)
      previousImports(i.toName) = i
  }
}
