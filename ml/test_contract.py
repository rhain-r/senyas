import unittest
import numpy as np
from features import frame_features, mirror_sequence, resample

class ContractTests(unittest.TestCase):
    def test_missing_is_finite_zero(self):
        np.testing.assert_array_equal(frame_features([], None, 640, 480), np.zeros(156))
    def test_local_translation_invariance(self):
        hand=np.array([[.2+i*.001,.3+i*.002,i*.0001] for i in range(21)])
        pose=np.zeros((33,4));pose[:,3]=1;pose[11,:2]=[.3,.4];pose[12,:2]=[.7,.4]
        a=frame_features([hand],pose,640,480)
        shifted=hand.copy();shifted[:,:2]+=.05
        np.testing.assert_allclose(a[:63],frame_features([shifted],pose,640,480)[:63],atol=1e-5)
        self.assertEqual(a[66],1);self.assertEqual(a[133],0);self.assertEqual(a[155],1)
    def test_mirror_twice_identity(self):
        rng=np.random.default_rng(8);x=rng.normal(size=(16,156)).astype('float32');x[:,66]=1;x[:,133]=0
        np.testing.assert_array_equal(mirror_sequence(mirror_sequence(x)),x)
    def test_resample_endpoints(self):
        x=np.arange(4*156,dtype='float32').reshape(4,156)
        y=resample(x,16)
        np.testing.assert_array_equal(y[0],x[0]);np.testing.assert_array_equal(y[-1],x[-1])

if __name__=='__main__':unittest.main()
