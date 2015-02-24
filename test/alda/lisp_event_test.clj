(ns alda.lisp-event-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer :all]
            [alda.lisp :refer :all]
            [alda.parser :refer :all]))

(deftest note-tests
  (part {:names ["piano"]}
    (let [piano            (fn [] (*instruments* "piano"))
          current-offset   (fn [] (:current-offset (piano)))
          last-offset      (fn [] (:last-offset (piano)))
          current-duration (fn [] (:duration (piano)))
          tempo            (fn [] (:tempo (piano)))
          octave           (fn [] (:octave (piano)))]
      (testing "a note event:"
        (let [start (current-offset)
              c ((pitch :c) (octave))
              {:keys [duration offset pitch]}
               (first (note (pitch :c) (duration (note-length 4) :slur)))]
         (testing "should be placed at the current offset"
           (is (== start offset)))
         (testing "should bump :current-offset forward by its duration"
           (is (== (+ start duration) (current-offset))))
         (testing "should update :last-offset"
           (is (== start (last-offset))))
         (testing "should have the pitch it was given"
           (is (== pitch c)))))
      (testing "a note event with no duration provided:"
        (let [default-dur-fn     (:duration-fn (duration (current-duration)))
              default-duration   (default-dur-fn (tempo))
              {:keys [duration]} (first (note (pitch :c) :slur))]
          (testing "the default duration (:duration) should be used"
            (is (== duration default-duration))))))))

(deftest chord-tests
  (part {:names ["piano"]}
    (let [piano          (fn [] (*instruments* "piano"))
          current-offset (fn [] (:current-offset (piano)))
          last-offset    (fn [] (:last-offset (piano)))
          tempo          (fn [] (:tempo (piano)))]
      (testing "a chord event:"
        (let [start (current-offset)
              {:keys [events]} (first
                                 (chord (note (pitch :c) (duration (note-length 1)))
                                       (note (pitch :e) (duration (note-length 4)))
                                       (pause (duration (note-length 8)))
                                       (note (pitch :g) (duration (note-length 2)))))]
          (testing "the notes should all start at the same time"
            (is (every? #(= start (:offset %)) events)))
          (testing ":current-offset should be bumped forward by the shortest
                    note/rest duration"
            (let [dur-fn (:duration-fn (duration (note-length 8)))
                  bump   (dur-fn (tempo))]
              (is (= (current-offset) (+ start bump)))))
          (testing ":last-offset should be updated correctly"
            (is (= (last-offset) start))))))))

(deftest voice-tests
  (part {:names ["piano"]}
    (let [piano          (fn [] (*instruments* "piano"))
          current-offset (fn [] (:current-offset (piano)))
          last-offset    (fn [] (:last-offset (piano)))
          tempo          (fn [] (:tempo (piano)))]
      (testing "a voice returns as many notes as it has"
        (is (= 5
               (count (voice 42
                        (note (pitch :c)) (note (pitch :d)) (note (pitch :e))
                        (note (pitch :f)) (note (pitch :g)))))))
      (testing "a voice has the notes that it has"
        (let [a-note (note (pitch :a))
              b-note (note (pitch :b))
              c-note (note (pitch :c))
              the-voice (voice 1 a-note b-note c-note)
              has-note? (fn [voice note]
                          "(a note is technically a list of Note records,
                            one for each instrument in *current-instruments*)"
                          (every? #(contains? (set voice) %) note))]
          (is (has-note? the-voice a-note))
          (is (has-note? the-voice b-note))
          (is (has-note? the-voice c-note))
          (is (= 3 (count the-voice)))))
      (testing "a voice group:"
        (let [start (current-offset)
              {:keys [v1 v2 v3]} (voices
                                   (voice 1
                                     (note (pitch :g) (duration (note-length 1)))
                                     (note (pitch :b) (duration (note-length 2))))
                                   (voice 2
                                     (note (pitch :b) (duration (note-length 1)))
                                     (note (pitch :d) (duration (note-length 1))))
                                   (voice 3
                                     (note (pitch :d) (duration (note-length 1)))
                                     (note (pitch :f) (duration (note-length 4))))
                                   (voice 2
                                     (octave :up)
                                     (octave :down)
                                     (note (pitch :g))
                                     (note (pitch :g))))]
          (testing "the first note of each voice should all start at the same time"
            (is (every? #(= start (:offset %)) (map first [v1 v2 v3]))))
          (testing "repeated calls to the same voice should append events"
            (is (= 6 (count v2))))
          (testing "the voice lasting the longest should bump :current-offset
                    forward by however long it takes to finish"
            (let [dur-fn (:duration-fn (duration (note-length 1)
                                                 (note-length 1)
                                                 (note-length 1)
                                                 (note-length 1)))
                  bump (dur-fn (tempo))]
              (is (= (current-offset) (+ start bump)))))
          (testing ":last-offset should be updated to the :last-offset as of the
                    point where the longest voice finishes"
            (let [dur-fn (:duration-fn (duration (note-length 1)
                                                 (note-length 1)
                                                 (note-length 1)))
                  bump (dur-fn (tempo))]
              (is (= (last-offset) (+ start bump))))))))))

(deftest global-attribute-tests
  (part {:names ["piano"]}
    (let [piano         (fn [] (*instruments* "piano"))
          current-tempo (fn [] (:tempo (piano)))]
      (testing "a global tempo change:"
        (set-current-offset "piano" 0)
        (tempo 120)
        (pause (duration (note-length 1)))
        (global-attribute :tempo 60)
        (testing "it should change the tempo"
          (is (= (current-tempo) 60)))
        (testing "when another part starts,"
          (set-current-offset "piano" 0)
          (tempo 120)
          (testing "the tempo should change once it encounters the global attribute"
            (is (= (current-tempo) 120)) ; not yet...
            (pause (duration (note-length 2 {:dots 1})))
            (is (= (current-tempo) 120)) ; not yet...
            (pause)
            (is (= (current-tempo) 60))))))) ; now!
  (alter-var-root #'*global-attributes* (constantly (sorted-map))))

(deftest marker-tests
  (part {:names ["piano"]}
    (let [piano              (fn [] (*instruments* "piano"))
          get-current-marker (fn [] (:current-marker (piano)))
          current-offset     (fn [] (:current-offset (piano)))]
      (testing "a marker:"
        (let [current-marker (get-current-marker)
              moment (current-offset)
              test-marker (marker "test-marker")]
          (testing "returns a Marker record"
            (is (= test-marker (alda.lisp.Marker. "test-marker" moment))))
          (testing "it should create an entry for the marker in *events*"
            (is (contains? *events* "test-marker")))
          (testing "its offset should be correct"
            (is (= moment (get-in *events* ["test-marker" :offset]))))
          (testing "placing a marker doesn't change the current marker"
            (is (= current-marker (get-current-marker))))))
      (testing "at-marker:"
        (pause (duration (note-length 1) (note-length 1)))
        (at-marker "test-marker")
        (testing "new events should be placed from the marker / offset 0"
          (is (= "test-marker" (get-current-marker)))
          (is (zero? (count (get-in *events* ["test-marker" :events]))))
          (let [first-note (first (note (pitch :d)))]
            (is (= 1 (count (get-in *events* ["test-marker" :events]))))
            (is (= 0 (:offset first-note))))))
      (testing "using at-marker before marker:"
        (testing "at-marker still works;"
          (at-marker "test-marker-2")
          (testing "it sets *current-marker*"
            (is (= "test-marker-2" (get-current-marker))))
          (testing "new events are placed from the marker / offset 0"
            (is (zero? (count (get-in *events* ["test-marker-2" :events]))))
            (let [first-note (first (note (pitch :e)))]
              (is (= 1 (count (get-in *events* ["test-marker-2" :events]))))
              (is (= 0 (:offset first-note))))))
        (testing "marker adds an offset to the marker"
          (let [moment (current-offset)]
            (marker "test-marker-2")
            (is (= moment (get-in *events* ["test-marker-2" :offset])))))))))