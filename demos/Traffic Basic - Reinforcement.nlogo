extensions [ py ]

globals [
  sample-car
  speed-limit
  speed-min
  inputs
  loss
]

turtles-own [
  speed
  reward
  state
  action ; 0 = decelerate, 1 = stay same, 2 = accelerate
  next-state
]

to setup-tf
  py:setup py:python
  (py:run
    "import numpy as np"
    "from keras.models import Sequential"
    "from keras.layers.core import Dense"
    "from keras import optimizers"
  )
  setup
end

to setup
  clear-all

  set inputs (list
    [-> speed]
    [-> distance next-car]
    [-> [speed] of next-car]
  )

  if fp-exp? [
    set inputs lput [-> exp-rate ] inputs
  ]
  if fp-ticks? [
    set inputs lput [-> ticks] inputs
  ]

  py:set "state_dims" length inputs
  py:set "hl_size" 36
  py:set "num_actions" 3
  py:set "memory_size" memory-size
  py:set "batch_size" batch-size
  py:set "lr" learning-rate

  (py:run
    "model = Sequential()"
    "model.add(Dense(hl_size, input_shape=(state_dims,), activation='relu'))"
    "model.add(Dense(hl_size, activation='relu'))"
    "model.add(Dense(hl_size, activation='relu'))"
    "model.add(Dense(num_actions))"
    "optimizer = optimizers.adam(lr=lr)"
    "model.compile(optimizer, 'mse')"
    "model.summary()"
    "memory = []")

  ask patches [ setup-road ]
  set speed-limit 1
  set speed-min 0
  setup-cars
  reset-ticks
end

to setup-road ;; patch procedure
  if pycor < 2 and pycor > -2 [ set pcolor white ]
end

to setup-cars
  if number-of-cars > world-width [
    user-message (word
      "There are too many cars for the amount of road. "
      "Please decrease the NUMBER-OF-CARS slider to below "
      (world-width + 1) " and press the SETUP button again. "
      "The setup has stopped.")
    stop
  ]
  set-default-shape turtles "car"
  create-turtles number-of-cars [
    set color blue
    set xcor random-xcor
    set heading 90
    ;; set initial speed to be in range 0.1 to 1.0
    set speed 0.1 + random-float 0.9
    separate-cars
  ]
  set sample-car one-of turtles
  ask sample-car [ set color red ]
end

; this procedure is needed so when we click "Setup" we
; don't end up with any two cars on the same patch
to separate-cars ;; turtle procedure
  if any? other turtles-here [
    fd 1
    separate-cars
  ]
end

to go
  py:set "discount" discount
  ;; if there is a car right ahead of you, match its speed then slow down
  select-actions
  ask turtles [
    if action = 0 [ decelerate set color red ]
    if action = 1 [ set color yellow ]
    if action = 2 [ accelerate set color green]
    ;; don't slow down below speed minimum or speed up beyond speed limit
    if distance next-car < 1 + speed [ slow-down-car next-car ]
    if speed < speed-min [ set speed speed-min ]
    if speed > speed-limit [ set speed speed-limit ]
    fd speed
    set reward (log (speed + 1e-8) 2)
    ;set reward speed
  ]
  if train? [
    remember
    train
  ]
  tick
end

to select-actions
  ask turtles [ set state map runresult inputs ]
  let turtle-list sort turtles
  py:set "states" map [ t -> [ state ] of t ] turtle-list
  let actions py:runresult "np.argmax(model.predict(np.array(states)), axis = 1)"

  (foreach turtle-list actions [ [t a] ->
    ask t [
      ifelse random-float 1 < exp-rate [
        set action random 3
      ] [
        set action a
      ]
    ]
  ])
end

to-report exp-rate
  report exploration-rate / (1 + exploration-decay-rate * ticks)
end

to remember
  ask turtles [ set next-state map runresult inputs ]
  let data [ (list state action reward next-state) ] of turtles
  py:set "new_exp" data
  (py:run
    "memory.extend(new_exp)"
    "if len(memory) > memory_size:"
    "    memory = memory[-memory_size:]")
end

to train
  (py:run
    "sample_ix = np.random.randint(len(memory), size = batch_size)"
    "inputs = np.array([memory[i][0] for i in sample_ix])"
    "actions = np.array([memory[i][1] for i in sample_ix])"
    "rewards = np.array([memory[i][2] for i in sample_ix])"
    "next_states = np.array([memory[i][3] for i in sample_ix])"
    "targets = model.predict(inputs)"
    "next_state_rewards = model.predict(next_states)"
    "next_state_qs = np.max(next_state_rewards, axis = 1)"
    "targets[np.arange(targets.shape[0]), actions] = rewards + discount * next_state_qs"
    "model.train_on_batch(inputs, targets)"
  )

end

to-report next-car
  let here min-one-of turtles-here with [ xcor > [ xcor ] of myself ] [ distance myself ]
  if here != nobody [ report here ]
  let i 1
  while [ not any? turtles-on patch-ahead i ] [
    set i i + 1
  ]
  report min-one-of turtles-on patch-ahead i [ distance myself ]
end


to slow-down-car [ car-ahead ] ;; turtle procedure
  ;; slow down so you are driving more slowly than the car ahead of you
  set speed [ speed ] of car-ahead - stop-penalty
end

to accelerate
  set speed speed + acceleration
end

to decelerate
  set speed speed - deceleration
end

; Copyright 1997 Uri Wilensky.
; See Info tab for full copyright and license.
@#$#@#$#@
GRAPHICS-WINDOW
15
480
703
609
-1
-1
13.33333333333334
1
10
1
1
1
0
1
0
1
-25
25
-4
4
1
1
1
ticks
30.0

BUTTON
110
190
182
231
NIL
setup
NIL
1
T
OBSERVER
NIL
NIL
NIL
NIL
1

BUTTON
193
191
264
231
NIL
go
T
1
T
OBSERVER
NIL
NIL
NIL
NIL
0

SLIDER
15
15
265
48
number-of-cars
number-of-cars
1
41
20.0
1
1
NIL
HORIZONTAL

SLIDER
15
270
265
303
deceleration
deceleration
0
.0099
0.0045
.0001
1
NIL
HORIZONTAL

SLIDER
15
235
265
268
acceleration
acceleration
0
.0099
0.0044
.0001
1
NIL
HORIZONTAL

PLOT
285
15
705
212
Car speeds
time
speed
0.0
300.0
0.0
1.0
true
false
"" ""
PENS
"sample car" 1.0 0 -2674135 true "" "plot [speed] of sample-car"
"min speed" 1.0 0 -13345367 true "" "plot min [speed] of turtles"
"max speed" 1.0 0 -10899396 true "" "plot max [speed] of turtles"

BUTTON
15
190
97
230
NIL
setup-tf
NIL
1
T
OBSERVER
NIL
NIL
NIL
NIL
1

SLIDER
15
410
265
443
discount
discount
0
1
0.9
0.01
1
NIL
HORIZONTAL

SLIDER
15
340
265
373
exploration-rate
exploration-rate
0
1
1.0
0.01
1
NIL
HORIZONTAL

PLOT
285
210
705
405
selected-actions
NIL
NIL
0.0
300.0
0.0
20.0
true
false
"set-plot-y-range 0 number-of-cars" ""
PENS
"default" 1.0 0 -2674135 true "" "plot count turtles with [ action = 0 ]"
"pen-1" 1.0 0 -1184463 true "" "plot count turtles with [ action = 1 ]"
"pen-2" 1.0 0 -10899396 true "" "plot count turtles with [ action = 2 ]"

SLIDER
15
305
265
338
stop-penalty
stop-penalty
0
.099
0.027
0.001
1
NIL
HORIZONTAL

SWITCH
15
445
117
478
train?
train?
0
1
-1000

SLIDER
15
50
265
83
memory-size
memory-size
0
1000000
10000.0
1000
1
NIL
HORIZONTAL

SLIDER
15
85
265
118
batch-size
batch-size
0
1024
128.0
32
1
NIL
HORIZONTAL

SLIDER
15
120
265
153
learning-rate
learning-rate
0
0.01
0.001
0.0001
1
NIL
HORIZONTAL

SLIDER
15
375
265
408
exploration-decay-rate
exploration-decay-rate
0
0.1
0.01
0.001
1
NIL
HORIZONTAL

MONITOR
285
405
352
450
NIL
exp-rate
4
1
11

SWITCH
15
155
140
188
fp-exp?
fp-exp?
0
1
-1000

SWITCH
145
155
265
188
fp-ticks?
fp-ticks?
1
1
-1000

@#$#@#$#@
## WHAT IS IT?

This model models the movement of cars on a highway. Each car follows a simple set of rules: it slows down (decelerates) if it sees a car close ahead, and speeds up (accelerates) if it doesn't see a car ahead. The model demonstrates how traffic jams can form even without any accidents, broken bridges, or overturned trucks.  No "centralized cause" is needed for a traffic jam to form.

## HOW TO USE IT

Click on the SETUP button to set up the cars.

Set the NUMBER-OF-CARS slider to change the number of cars on the road.

Click on GO to start the cars moving.  Note that they wrap around the world as they move, so the road is like a continuous loop.

The ACCELERATION slider controls the rate at which cars accelerate (speed up) when there are no cars ahead.

When a car sees another car right in front, it matches that car's speed and then slows down a bit more.  How much slower it goes than the car in front of it is controlled by the DECELERATION slider.

## THINGS TO NOTICE

Traffic jams can start from small "seeds."  These cars start with random positions and random speeds. If some cars are clustered together, they will move slowly, causing cars behind them to slow down, and a traffic jam forms.

Even though all of the cars are moving forward, the traffic jams tend to move backwards. This behavior is common in wave phenomena: the behavior of the group is often very different from the behavior of the individuals that make up the group.

The plot shows three values as the model runs:

* the fastest speed of any car (this doesn't exceed the speed limit!)

* the slowest speed of any car

* the speed of a single car (turtle 0), painted red so it can be watched.

Notice not only the maximum and minimum, but also the variability -- the "jerkiness" of one vehicle.

Notice that the default settings have cars decelerating much faster than they accelerate. This is typical of traffic flow models.

Even though both ACCELERATION and DECELERATION are very small, the cars can achieve high speeds as these values are added or subtracted at each tick.

## THINGS TO TRY

In this model there are three sliders that can affect the tendency to create traffic jams: the initial NUMBER-OF-CARS, ACCELERATION, and DECELERATION.

Look for patterns in how these settings affect the traffic flow.  Which variable has the greatest effect?  Do the patterns make sense?  Do they seem to be consistent with your driving experiences?

Set DECELERATION to zero.  What happens to the flow?  Gradually increase DECELERATION while the model runs.  At what point does the flow "break down"?

## EXTENDING THE MODEL

Try other rules for speeding up and slowing down.  Is the rule presented here realistic? Are there other rules that are more accurate or represent better driving strategies?

In reality, different vehicles may follow different rules. Try giving different rules or ACCELERATION/DECELERATION values to some of the cars.  Can one bad driver mess things up?

The asymmetry between acceleration and deceleration is a simplified representation of different driving habits and response times. Can you explicitly encode these into the model?

What could you change to minimize the chances of traffic jams forming?

What could you change to make traffic jams move forward rather than backward?

Make a model of two-lane traffic.

## NETLOGO FEATURES

The plot shows both global values and the value for a single car, which helps one watch overall patterns and individual behavior at the same time.

The `watch` command is used to make it easier to focus on the red car.

The `speed-limit` and `speed-min` variables are set to constant values. Since they are the same for every car, these variables could have been defined as globals rather than turtle variables. We have specified them as turtle variables since modifications or extensions to this model might well have every car with its own speed-limit values.

## RELATED MODELS

- "Traffic Basic Utility": a version of "Traffic Basic" including a utility function for the cars.

- "Traffic Basic Adaptive": a version of "Traffic Basic" where cars adapt their acceleration to try and maintain a smooth flow of traffic.

- "Traffic Basic Adaptive Individuals": a version of "Traffic Basic Adaptive" where each car adapts individually, instead of all cars adapting in unison.

- "Traffic 2 Lanes": a more sophisticated two-lane version of the "Traffic Basic" model.

- "Traffic Intersection": a model of cars traveling through a single intersection.

- "Traffic Grid": a model of traffic moving in a city grid, with stoplights at the intersections.

- "Traffic Grid Goal": a version of "Traffic Grid" where the cars have goals, namely to drive to and from work.

- "Gridlock HubNet": a version of "Traffic Grid" where students control traffic lights in real-time.

- "Gridlock Alternate HubNet": a version of "Gridlock HubNet" where students can enter NetLogo code to plot custom metrics.

## HOW TO CITE

If you mention this model or the NetLogo software in a publication, we ask that you include the citations below.

For the model itself:

* Wilensky, U. (1997).  NetLogo Traffic Basic model.  http://ccl.northwestern.edu/netlogo/models/TrafficBasic.  Center for Connected Learning and Computer-Based Modeling, Northwestern University, Evanston, IL.

Please cite the NetLogo software as:

* Wilensky, U. (1999). NetLogo. http://ccl.northwestern.edu/netlogo/. Center for Connected Learning and Computer-Based Modeling, Northwestern University, Evanston, IL.

## COPYRIGHT AND LICENSE

Copyright 1997 Uri Wilensky.

![CC BY-NC-SA 3.0](http://ccl.northwestern.edu/images/creativecommons/byncsa.png)

This work is licensed under the Creative Commons Attribution-NonCommercial-ShareAlike 3.0 License.  To view a copy of this license, visit https://creativecommons.org/licenses/by-nc-sa/3.0/ or send a letter to Creative Commons, 559 Nathan Abbott Way, Stanford, California 94305, USA.

Commercial licenses are also available. To inquire about commercial licenses, please contact Uri Wilensky at uri@northwestern.edu.

This model was created as part of the project: CONNECTED MATHEMATICS: MAKING SENSE OF COMPLEX PHENOMENA THROUGH BUILDING OBJECT-BASED PARALLEL MODELS (OBPML).  The project gratefully acknowledges the support of the National Science Foundation (Applications of Advanced Technologies Program) -- grant numbers RED #9552950 and REC #9632612.

This model was developed at the MIT Media Lab using CM StarLogo.  See Resnick, M. (1994) "Turtles, Termites and Traffic Jams: Explorations in Massively Parallel Microworlds."  Cambridge, MA: MIT Press.  Adapted to StarLogoT, 1997, as part of the Connected Mathematics Project.

This model was converted to NetLogo as part of the projects: PARTICIPATORY SIMULATIONS: NETWORK-BASED DESIGN FOR SYSTEMS LEARNING IN CLASSROOMS and/or INTEGRATED SIMULATION AND MODELING ENVIRONMENT. The project gratefully acknowledges the support of the National Science Foundation (REPP & ROLE programs) -- grant numbers REC #9814682 and REC-0126227. Converted from StarLogoT to NetLogo, 2001.

<!-- 1997 2001 MIT -->
@#$#@#$#@
default
true
0
Polygon -7500403 true true 150 5 40 250 150 205 260 250

airplane
true
0
Polygon -7500403 true true 150 0 135 15 120 60 120 105 15 165 15 195 120 180 135 240 105 270 120 285 150 270 180 285 210 270 165 240 180 180 285 195 285 165 180 105 180 60 165 15

arrow
true
0
Polygon -7500403 true true 150 0 0 150 105 150 105 293 195 293 195 150 300 150

box
false
0
Polygon -7500403 true true 150 285 285 225 285 75 150 135
Polygon -7500403 true true 150 135 15 75 150 15 285 75
Polygon -7500403 true true 15 75 15 225 150 285 150 135
Line -16777216 false 150 285 150 135
Line -16777216 false 150 135 15 75
Line -16777216 false 150 135 285 75

bug
true
0
Circle -7500403 true true 96 182 108
Circle -7500403 true true 110 127 80
Circle -7500403 true true 110 75 80
Line -7500403 true 150 100 80 30
Line -7500403 true 150 100 220 30

butterfly
true
0
Polygon -7500403 true true 150 165 209 199 225 225 225 255 195 270 165 255 150 240
Polygon -7500403 true true 150 165 89 198 75 225 75 255 105 270 135 255 150 240
Polygon -7500403 true true 139 148 100 105 55 90 25 90 10 105 10 135 25 180 40 195 85 194 139 163
Polygon -7500403 true true 162 150 200 105 245 90 275 90 290 105 290 135 275 180 260 195 215 195 162 165
Polygon -16777216 true false 150 255 135 225 120 150 135 120 150 105 165 120 180 150 165 225
Circle -16777216 true false 135 90 30
Line -16777216 false 150 105 195 60
Line -16777216 false 150 105 105 60

car
false
0
Polygon -7500403 true true 300 180 279 164 261 144 240 135 226 132 213 106 203 84 185 63 159 50 135 50 75 60 0 150 0 165 0 225 300 225 300 180
Circle -16777216 true false 180 180 90
Circle -16777216 true false 30 180 90
Polygon -16777216 true false 162 80 132 78 134 135 209 135 194 105 189 96 180 89
Circle -7500403 true true 47 195 58
Circle -7500403 true true 195 195 58

circle
false
0
Circle -7500403 true true 0 0 300

circle 2
false
0
Circle -7500403 true true 0 0 300
Circle -16777216 true false 30 30 240

cow
false
0
Polygon -7500403 true true 200 193 197 249 179 249 177 196 166 187 140 189 93 191 78 179 72 211 49 209 48 181 37 149 25 120 25 89 45 72 103 84 179 75 198 76 252 64 272 81 293 103 285 121 255 121 242 118 224 167
Polygon -7500403 true true 73 210 86 251 62 249 48 208
Polygon -7500403 true true 25 114 16 195 9 204 23 213 25 200 39 123

cylinder
false
0
Circle -7500403 true true 0 0 300

dot
false
0
Circle -7500403 true true 90 90 120

face happy
false
0
Circle -7500403 true true 8 8 285
Circle -16777216 true false 60 75 60
Circle -16777216 true false 180 75 60
Polygon -16777216 true false 150 255 90 239 62 213 47 191 67 179 90 203 109 218 150 225 192 218 210 203 227 181 251 194 236 217 212 240

face neutral
false
0
Circle -7500403 true true 8 7 285
Circle -16777216 true false 60 75 60
Circle -16777216 true false 180 75 60
Rectangle -16777216 true false 60 195 240 225

face sad
false
0
Circle -7500403 true true 8 8 285
Circle -16777216 true false 60 75 60
Circle -16777216 true false 180 75 60
Polygon -16777216 true false 150 168 90 184 62 210 47 232 67 244 90 220 109 205 150 198 192 205 210 220 227 242 251 229 236 206 212 183

fish
false
0
Polygon -1 true false 44 131 21 87 15 86 0 120 15 150 0 180 13 214 20 212 45 166
Polygon -1 true false 135 195 119 235 95 218 76 210 46 204 60 165
Polygon -1 true false 75 45 83 77 71 103 86 114 166 78 135 60
Polygon -7500403 true true 30 136 151 77 226 81 280 119 292 146 292 160 287 170 270 195 195 210 151 212 30 166
Circle -16777216 true false 215 106 30

flag
false
0
Rectangle -7500403 true true 60 15 75 300
Polygon -7500403 true true 90 150 270 90 90 30
Line -7500403 true 75 135 90 135
Line -7500403 true 75 45 90 45

flower
false
0
Polygon -10899396 true false 135 120 165 165 180 210 180 240 150 300 165 300 195 240 195 195 165 135
Circle -7500403 true true 85 132 38
Circle -7500403 true true 130 147 38
Circle -7500403 true true 192 85 38
Circle -7500403 true true 85 40 38
Circle -7500403 true true 177 40 38
Circle -7500403 true true 177 132 38
Circle -7500403 true true 70 85 38
Circle -7500403 true true 130 25 38
Circle -7500403 true true 96 51 108
Circle -16777216 true false 113 68 74
Polygon -10899396 true false 189 233 219 188 249 173 279 188 234 218
Polygon -10899396 true false 180 255 150 210 105 210 75 240 135 240

house
false
0
Rectangle -7500403 true true 45 120 255 285
Rectangle -16777216 true false 120 210 180 285
Polygon -7500403 true true 15 120 150 15 285 120
Line -16777216 false 30 120 270 120

leaf
false
0
Polygon -7500403 true true 150 210 135 195 120 210 60 210 30 195 60 180 60 165 15 135 30 120 15 105 40 104 45 90 60 90 90 105 105 120 120 120 105 60 120 60 135 30 150 15 165 30 180 60 195 60 180 120 195 120 210 105 240 90 255 90 263 104 285 105 270 120 285 135 240 165 240 180 270 195 240 210 180 210 165 195
Polygon -7500403 true true 135 195 135 240 120 255 105 255 105 285 135 285 165 240 165 195

line
true
0
Line -7500403 true 150 0 150 300

line half
true
0
Line -7500403 true 150 0 150 150

pentagon
false
0
Polygon -7500403 true true 150 15 15 120 60 285 240 285 285 120

person
false
0
Circle -7500403 true true 110 5 80
Polygon -7500403 true true 105 90 120 195 90 285 105 300 135 300 150 225 165 300 195 300 210 285 180 195 195 90
Rectangle -7500403 true true 127 79 172 94
Polygon -7500403 true true 195 90 240 150 225 180 165 105
Polygon -7500403 true true 105 90 60 150 75 180 135 105

plant
false
0
Rectangle -7500403 true true 135 90 165 300
Polygon -7500403 true true 135 255 90 210 45 195 75 255 135 285
Polygon -7500403 true true 165 255 210 210 255 195 225 255 165 285
Polygon -7500403 true true 135 180 90 135 45 120 75 180 135 210
Polygon -7500403 true true 165 180 165 210 225 180 255 120 210 135
Polygon -7500403 true true 135 105 90 60 45 45 75 105 135 135
Polygon -7500403 true true 165 105 165 135 225 105 255 45 210 60
Polygon -7500403 true true 135 90 120 45 150 15 180 45 165 90

square
false
0
Rectangle -7500403 true true 30 30 270 270

square 2
false
0
Rectangle -7500403 true true 30 30 270 270
Rectangle -16777216 true false 60 60 240 240

star
false
0
Polygon -7500403 true true 151 1 185 108 298 108 207 175 242 282 151 216 59 282 94 175 3 108 116 108

target
false
0
Circle -7500403 true true 0 0 300
Circle -16777216 true false 30 30 240
Circle -7500403 true true 60 60 180
Circle -16777216 true false 90 90 120
Circle -7500403 true true 120 120 60

tree
false
0
Circle -7500403 true true 118 3 94
Rectangle -6459832 true false 120 195 180 300
Circle -7500403 true true 65 21 108
Circle -7500403 true true 116 41 127
Circle -7500403 true true 45 90 120
Circle -7500403 true true 104 74 152

triangle
false
0
Polygon -7500403 true true 150 30 15 255 285 255

triangle 2
false
0
Polygon -7500403 true true 150 30 15 255 285 255
Polygon -16777216 true false 151 99 225 223 75 224

truck
false
0
Rectangle -7500403 true true 4 45 195 187
Polygon -7500403 true true 296 193 296 150 259 134 244 104 208 104 207 194
Rectangle -1 true false 195 60 195 105
Polygon -16777216 true false 238 112 252 141 219 141 218 112
Circle -16777216 true false 234 174 42
Rectangle -7500403 true true 181 185 214 194
Circle -16777216 true false 144 174 42
Circle -16777216 true false 24 174 42
Circle -7500403 false true 24 174 42
Circle -7500403 false true 144 174 42
Circle -7500403 false true 234 174 42

turtle
true
0
Polygon -10899396 true false 215 204 240 233 246 254 228 266 215 252 193 210
Polygon -10899396 true false 195 90 225 75 245 75 260 89 269 108 261 124 240 105 225 105 210 105
Polygon -10899396 true false 105 90 75 75 55 75 40 89 31 108 39 124 60 105 75 105 90 105
Polygon -10899396 true false 132 85 134 64 107 51 108 17 150 2 192 18 192 52 169 65 172 87
Polygon -10899396 true false 85 204 60 233 54 254 72 266 85 252 107 210
Polygon -7500403 true true 119 75 179 75 209 101 224 135 220 225 175 261 128 261 81 224 74 135 88 99

wheel
false
0
Circle -7500403 true true 3 3 294
Circle -16777216 true false 30 30 240
Line -7500403 true 150 285 150 15
Line -7500403 true 15 150 285 150
Circle -7500403 true true 120 120 60
Line -7500403 true 216 40 79 269
Line -7500403 true 40 84 269 221
Line -7500403 true 40 216 269 79
Line -7500403 true 84 40 221 269

x
false
0
Polygon -7500403 true true 270 75 225 30 30 225 75 270
Polygon -7500403 true true 30 75 75 30 270 225 225 270
@#$#@#$#@
NetLogo 6.0.2
@#$#@#$#@
setup
repeat 180 [ go ]
@#$#@#$#@
@#$#@#$#@
@#$#@#$#@
@#$#@#$#@
default
0.0
-0.2 0 0.0 1.0
0.0 1 1.0 0.0
0.2 0 0.0 1.0
link direction
true
0
Line -7500403 true 150 150 90 180
Line -7500403 true 150 150 210 180
@#$#@#$#@
1
@#$#@#$#@
