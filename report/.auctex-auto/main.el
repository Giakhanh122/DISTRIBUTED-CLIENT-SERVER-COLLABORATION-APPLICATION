;; -*- lexical-binding: t; -*-

(TeX-add-style-hook
 "main"
 (lambda ()
   (TeX-add-to-alist 'LaTeX-provided-class-options
                     '(("Layout/main-layout" "oneside")))
   (TeX-run-style-hooks
    "latex2e"
    "Headers/lib"
    "Layout/cover"
    "Layout/table-of-content"
    "Sections/main"
    "Layout/main-layout"
    "Layout/main-layout10"))
 :latex)

