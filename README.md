
# NetLogo Python extension

This NetLogo extension allows you to run Python code from NetLogo. It works with both Python 2 and 3, and should work with almost all Python libraries.

## Building

Run `sbt package`.

If compilation succeeds, `py.jar` will be created. This file and `pyext.py` should then be placed in a folder named `py` in your NetLogo `extensions` directory.

## Configuring

By default, the `py:python2`, `py:python3`, and `py:python` commands will attempt to find a Python executable of the appropriate version.
If you'd like to change which Python executable they use, or they can't find a Python executable, you should configure which Python executables to use.
You can do this by either:

- Using the configuration menu under the Python toolbar menu that appears when you use a model that uses the Python extension.
- Editing the `python.properties` file that appears in the Python extension installation folder as follows:

```
python3=/path/to/python3
python2=/path/to/python2
```

## Primitives


### `py:setup`

```NetLogo
py:setup python-executable
```


Create the Python session that this extension will use to execute code. The session will be started with the given Python executable. This command *must* be run before running any other Python extension primitive. Running this command again will shutdown the current Python environment and start a new one.

 The executable may be specified as a relative path, absolute path, or just the executable name if it is on your PATH.
 Furthermore, this extension offers a few helper primitives for getting particular versions of Python in system
 independent ways.

 In general, unless working with a virtual environment or a specific system setup, you should do:

 ```NetLogo
 py:setup py:python  ; if your code works with either Python 2 or 3
 py:setup py:python3 ; for Python 3
 py:setup py:python2 ; for Python 2
 ```

`py:setup` may be invoked by directly referring to different Pythons as well. For instance:

```NetLogo
py:setup "python3" ; if `python3` is on your PATH
py:setup "python"  ; if `python` is on your PATH
```

If you use virtualenv or Conda, simply specify the path of the Python executable in the environment you wish to use:

```NetLogo
py:setup "/path/to/myenv/bin/python"
```

The path may be relative or absolute. So, if you have a virtual environment in the same folder as your model, you can do:

```NetLogo
py:setup "myenv/bin/python"
```
    


### `py:python`

```NetLogo
py:python
```


Reports either the path to the latest version of Python configured in the `python.properties` file or, if that is blank, looks for a Python executable on your system's PATH.
For Windows, there is an installation option for including Python on your PATH.
For MacOS and Linux, it will likely already be on your PATH.
The output of this reporter is meant to be used with `py:setup`, but you may also use it to see which Python installation this extension will use by default.

For example, on MacOS with Homebrew installed Python 3:
```NetLogo
observer> show py:python
observer: "/usr/local/bin/python3"
```



### `py:python2`

```NetLogo
py:python2
```


Reports either the path to Python 2 configured in the `python.properties` file or, if that is blank, looks for a Python 2 executable on your system's PATH.
For Windows, there is an installation option for including Python on your PATH.
For MacOS and Linux, it will likely already be on your PATH.
The output of this reporter is meant to be used with `py:setup`, but you may also use it to see which Python 2 installation this extension will use by default.

For example, on MacOS with Homebrew installed Python 2:
```NetLogo
observer> show py:python2
observer: "/usr/local/bin/python2"
```



### `py:python3`

```NetLogo
py:python3
```


Reports either the path to Python 3 configured in the `python.properties` file or, if that is blank, looks for a Python 3 executable on your system's PATH.
For Windows, there is an installation option for including Python on your PATH.
For MacOS and Linux, it will likely already be on your PATH.
The output of this reporter is meant to be used with `py:setup`, but you may also use it to see which Python 3 installation this extension will use by default.

For example, on MacOS with Homebrew installed Python 3:
```NetLogo
observer> show py:python
observer: "/usr/local/bin/python3"
```



### `py:run`

```NetLogo
py:run python-statement
```


Runs the given Python statements in the current Python session. To make multi-line Python code easier to run, this command will take multiple strings, each of which will be interpreted as a separate line of Python code. For instance:

```NetLogo
(py:run
  "import matplotlib"
  "matplotlib.use('TkAgg')"
  "import numpy as np"
  "import matplotlib.pyplot as plt"
  "for i in range(10):"
  "    plt.plot([ x ** i for x in arange(-1, 1, 0.1) ])"
  "plt.show()"
)
```

`py:run` will wait for the statements to finish running before continuing. Thus, if you have long running Python code, NetLogo will pause while it runs.



### `py:runresult`

```NetLogo
py:runresult python-expression
```


Evaluates the given Python expression and reports the result.
`py:runresult` attempts to convert from Python data types to NetLogo data types.
Numbers, strings, and booleans convert as you would expect.
Any list-like object in Python (that is, anything with a length that you can iterate through) will be converted to a NetLogo list.
For instance, Python lists and NumPy arrays will convert to NetLogo lists.
Python dicts (and dict-like objects) will convert to a NetLogo list of key-value pairs (where each pair is represented as a list).
`None` will be converted to `nobody`.
Other objects will simply be converted to a string representation.

Note that due a [current issue](https://github.com/qiemem/PythonExtension/issues/6), dict keys will always be reported as strings.
If you need to report non-string keys, report the `.items()` of the dict instead of the dict itself.



### `py:set`

```NetLogo
py:set variable-name value
```


Sets a variable in the Python session with the given name to the given NetLogo value. NetLogo objects will be converted to Python objects as expected. `value` should only be a number, string, boolean, list, or nobody (agents and extension objects are currently converted to strings).

```NetLogo
py:set "x" [1 2 3]
show py:runresult "x" ;; Shows [1 2 3]
```


