(ns wayland.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [wayland.model :as model]
            [wayland.objects :as objects]
            [wayland.wire :as wire]))

(deftest model-covers-core-interfaces
  (is (= "wl_display" (:interface/name (model/interface "wl_display"))))
  (is (some #(= "sync" (:request/name %)) (:requests (model/interface "wl_display"))))
  (is (some #(= "done" (:event/name %)) (:events (model/interface "wl_callback"))))
  (testing "every arg type is a wire type"
    (doseq [iface model/core-interfaces
            m (concat (:requests iface) (:events iface))
            a (or (:args m) (:request/args m) (:event/args m))]
      (is (contains? model/wire-types (:arg/type a))
          (str (:interface/name iface) " arg " a)))))

(deftest object-table-discipline
  (let [t (-> objects/empty-table
              (objects/create 1 "wl_display" 1))]
    (testing "create then live"
      (is (objects/live? t 1)))
    (testing "id reuse of a live object fails closed"
      (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error) #"already live"
                            (objects/create t 1 "wl_surface" 6))))
    (testing "destroy then double-destroy is an error"
      (let [t' (objects/destroy t 1)]
        (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error) #"already-dead"
                              (objects/destroy t' 1)))))
    (testing "destroy of unknown id is an error"
      (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error) #"unknown object"
                            (objects/destroy t 42))))))

(deftest wire-step-validates-target-and-method
  (let [t (-> objects/empty-table
              (objects/create 1 "wl_display" 1))]
    (testing "live target + real request applies"
      (let [{:keys [table allocated]}
            (wire/wire-step t {:kind :request :interface "wl_display"
                               :request/name "get_registry"
                               :object-id 1 :new-ids ["wl_registry"]})]
        (is (= 1 (count allocated)))
        (is (objects/live? table (first allocated)))))
    (testing "request to unknown id = :invalid-object"
      (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error) #"unknown or dead"
                            (wire/wire-step t {:kind :request :interface "wl_display"
                                               :request/name "sync" :object-id 99}))))
    (testing "interface does not carry the method = :invalid-method"
      (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error) #"does not carry"
                            (wire/wire-step t {:kind :request :interface "wl_display"
                                               :request/name "destroy" :object-id 1}))))))

(deftest dead-object-rejects-messages
  (let [t (-> objects/empty-table
              (objects/create 1 "wl_display" 1)
              (objects/create 2 "wl_surface" 6)
              (objects/destroy 2))]
    (is (thrown-with-msg? #?(:clj Throwable :cljs js/Error) #"unknown or dead"
                          (wire/wire-step t {:kind :request :interface "wl_surface"
                                             :request/name "commit" :object-id 2})))))

(deftest server-ids-are-above-the-line
  (is (= :client-assigned (objects/alloc-state 1)))
  (is (= :server-assigned (objects/alloc-state 0xff000000))))
